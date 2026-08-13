package io.tapstate.adapters.pdk;

import io.tapstate.core.common.TapstateException;
import io.tapstate.spi.store.ConnectionConfig;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A bounded pool of live connector instances, keyed by {@link ConnectionFingerprint}. Opening a
 * connector is not one database connection: it is a fresh isolated class loader, a linked jar, a
 * constructed connector and — once initialized — whatever the connector builds at startup, which for
 * a database connector is a driver connection pool of its own. A face that opens one per query cannot
 * carry a caller that polls once a second, so instances are opened once, handed out one caller at a
 * time, and reused.
 *
 * <p>What the pool holds is an <em>initialized</em> instance. Opening alone builds no connection —
 * the driver's pool appears when the connector is initialized — so a pool that cached un-initialized
 * instances would rebuild that pool on every use and save nothing. The consequence runs the other
 * way too: an idle instance is holding real connections, so evicting it is a resource measure rather
 * than housekeeping.
 *
 * <p>Four limits, each closing a different failure:
 *
 * <ul>
 *   <li><b>per connection</b> — how many callers may be in flight against one set of settings at
 *       once, and therefore how many instances of it exist. An instance serves one caller at a time:
 *       the frozen contract gives an instance to a node, and nowhere promises a function may be
 *       called concurrently.
 *   <li><b>acquire</b> — how long a caller waits for a free instance, <em>fairly</em>. Fairness is
 *       what keeps a once-a-second poll from taking the instance back the moment it frees up and
 *       starving an interactive query, which would only ever see this timeout.
 *   <li><b>call</b> — how long one call may run before its instance is thrown away rather than
 *       returned. The call is still inside that instance, so handing it back would give the next
 *       caller a connector already stuck in someone else's query.
 *   <li><b>total</b> — the host-wide instance ceiling, counted in instances because each one
 *       multiplies out to a driver connection pool. Over the ceiling, the instance idle longest is
 *       closed to make room; with nothing idle to close, the request is refused.
 * </ul>
 *
 * <p>The pool holds no timer of its own: {@link #sweep()} is what evicts, and its owner is what
 * schedules it. Nothing else in here needs a thread, and a pool that started one would be a pool
 * every test has to race against.
 */
final class ConnectorInstancePool<T> implements AutoCloseable {

    /** One call against a checked-out instance; it may throw whatever the connector throws. */
    @FunctionalInterface
    interface PooledCall<T, R> {
        R run(T instance) throws Exception;
    }

    /** What the pool is bounded by; see the class comment for what each one closes. */
    record Limits(int perConnection, int total, Duration acquire, Duration call, Duration idle) {

        Limits withPerConnection(int value) {
            return new Limits(value, total, acquire, call, idle);
        }

        Limits withTotal(int value) {
            return new Limits(perConnection, value, acquire, call, idle);
        }

        Limits withAcquire(Duration value) {
            return new Limits(perConnection, total, value, call, idle);
        }

        Limits withCall(Duration value) {
            return new Limits(perConnection, total, acquire, value, idle);
        }

        Limits withIdle(Duration value) {
            return new Limits(perConnection, total, acquire, call, value);
        }
    }

    /**
     * The shipped limits. Three instances per connection bounds the connection multiplication a
     * database driver performs behind each one; sixteen bounds it across every connection at once.
     */
    static final Limits DEFAULTS = new Limits(
            3, 16, Duration.ofSeconds(2), Duration.ofSeconds(10), Duration.ofMinutes(5));

    private final Function<ConnectionConfig, T> open;
    private final Consumer<T> dispose;
    private final Limits limits;
    private final Clock clock;
    private final ExecutorService calls;

    private final ReentrantLock lock = new ReentrantLock();
    private final Map<String, Slot> slots = new LinkedHashMap<>();
    private final AtomicLong returns = new AtomicLong();
    private int live;
    private boolean closed;

    ConnectorInstancePool(Function<ConnectionConfig, T> open, Consumer<T> dispose, Limits limits, Clock clock) {
        this.open = open;
        this.dispose = dispose;
        this.limits = limits;
        this.clock = clock;
        // Calls run off the caller's thread so one that never returns can be abandoned at the call
        // limit; daemon threads so an abandoned call cannot hold the host open on the way out.
        this.calls = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "data-browser-call");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Runs {@code action} against an instance opened for {@code config}, reusing a pooled one when
     * there is one. The instance goes back to the pool afterwards — unless the call outran the call
     * limit, in which case it is thrown away instead.
     */
    <R> R call(ConnectionConfig config, PooledCall<T, R> action) {
        Lease lease = acquire(config);
        R result;
        try {
            result = run(config, lease, action);
        } catch (Poisoned poisoned) {
            // The pool abandoned the call while it was still running inside this instance, so the
            // instance goes away with it. This is deliberately not "the call threw a code": the read
            // face reports a connector's own refusal that way too, and that connection is still fine.
            discard(lease);
            throw poisoned.coded;
        } catch (RuntimeException | Error failed) {
            // The connector reported a failure and is still healthy - a failed query is not a failed
            // connection - so the instance goes back. Treating every failure as poison would reopen a
            // connector for every bad query.
            release(lease);
            throw failed;
        }
        release(lease);
        return result;
    }

    /** Closes every instance that has been idle at least as long as the idle limit. */
    void sweep() {
        long now = clock.millis();
        List<T> evicted = new ArrayList<>();
        lock.lock();
        try {
            for (Slot slot : slots.values()) {
                slot.idle.removeIf(entry -> {
                    if (now - entry.idleSince < limits.idle().toMillis()) {
                        return false;
                    }
                    evicted.add(entry.instance);
                    slot.live--;
                    live--;
                    return true;
                });
            }
        } finally {
            lock.unlock();
        }
        evicted.forEach(this::disposeQuietly);
    }

    /** Closes every idle instance and refuses further calls; a call in flight keeps its instance. */
    @Override
    public void close() {
        List<T> remaining = new ArrayList<>();
        lock.lock();
        try {
            closed = true;
            for (Slot slot : slots.values()) {
                slot.idle.forEach(entry -> remaining.add(entry.instance));
                live -= slot.idle.size();
                slot.live -= slot.idle.size();
                slot.idle.clear();
            }
        } finally {
            lock.unlock();
        }
        remaining.forEach(this::disposeQuietly);
        calls.shutdownNow();
    }

    // ---- acquire / release -----------------------------------------------------------------------

    /**
     * Takes one caller's turn against {@code config}: an idle instance if there is one, a fresh one if
     * the ceilings allow, and a coded refusal if neither is true within the acquire limit.
     */
    private Lease acquire(ConnectionConfig config) {
        String key = ConnectionFingerprint.of(config);
        Slot slot = slotFor(key);
        // Fair, so the turn goes to whoever has waited longest rather than to whoever asks most often.
        boolean permitted;
        try {
            permitted = slot.turns.tryAcquire(limits.acquire().toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw busy(config, e);
        }
        if (!permitted) {
            throw busy(config, null);
        }
        boolean handedOut = false;
        try {
            T pooled = takeIdle(slot);
            if (pooled != null) {
                handedOut = true;
                return new Lease(slot, pooled);
            }
            T evicted = reserve(slot);
            disposeQuietly(evicted);
            T fresh;
            try {
                fresh = open.apply(config);
            } catch (RuntimeException | Error e) {
                unreserve(slot);
                throw e;
            }
            handedOut = true;
            return new Lease(slot, fresh);
        } finally {
            if (!handedOut) {
                slot.turns.release();
            }
        }
    }

    private Slot slotFor(String key) {
        lock.lock();
        try {
            return slots.computeIfAbsent(key, ignored -> new Slot(limits.perConnection()));
        } finally {
            lock.unlock();
        }
    }

    /** The warmest idle instance of {@code slot}, or null when it holds none. */
    private T takeIdle(Slot slot) {
        lock.lock();
        try {
            Idle<T> entry = slot.idle.pollFirst();
            return entry == null ? null : entry.instance;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Books room for one more instance, returning the instance evicted to make it — the one idle
     * longest across every connection — or null when none had to go. Refuses with a code when the
     * ceiling is reached and nothing is idle to give up.
     */
    private T reserve(Slot slot) {
        lock.lock();
        try {
            T evicted = null;
            if (live >= limits.total()) {
                evicted = takeIdleLongest();
                if (evicted == null) {
                    throw new TapstateException(ConnectorError.INSTANCE_LIMIT_REACHED,
                            Map.of("limit", String.valueOf(limits.total())), null);
                }
            }
            slot.live++;
            live++;
            return evicted;
        } finally {
            lock.unlock();
        }
    }

    private void unreserve(Slot slot) {
        lock.lock();
        try {
            slot.live--;
            live--;
        } finally {
            lock.unlock();
        }
    }

    /** Removes and returns the instance idle longest across every connection, or null when none is. */
    private T takeIdleLongest() {
        Slot oldestSlot = null;
        Idle<T> oldest = null;
        for (Slot slot : slots.values()) {
            Idle<T> candidate = slot.idle.peekLast();
            // Ordered by when each was returned rather than by the clock: two instances returned inside
            // one clock tick are still a definite order, and eviction has to pick exactly one of them.
            if (candidate != null && (oldest == null || candidate.returned < oldest.returned)) {
                oldest = candidate;
                oldestSlot = slot;
            }
        }
        if (oldest == null) {
            return null;
        }
        oldestSlot.idle.pollLast();
        oldestSlot.live--;
        live--;
        return oldest.instance;
    }

    /** Returns a healthy instance to its slot, warmest first, and gives up the caller's turn. */
    private void release(Lease lease) {
        boolean keep;
        lock.lock();
        try {
            keep = !closed;
            if (keep) {
                lease.slot.idle.addFirst(new Idle<>(lease.instance, clock.millis(), returns.incrementAndGet()));
            } else {
                lease.slot.live--;
                live--;
            }
        } finally {
            lock.unlock();
        }
        lease.slot.turns.release();
        if (!keep) {
            disposeQuietly(lease.instance);
        }
    }

    /** Throws an instance away instead of pooling it, and gives up the caller's turn. */
    private void discard(Lease lease) {
        lock.lock();
        try {
            lease.slot.live--;
            live--;
        } finally {
            lock.unlock();
        }
        lease.slot.turns.release();
        disposeQuietly(lease.instance);
    }

    private void disposeQuietly(T instance) {
        if (instance == null) {
            return;
        }
        try {
            dispose.accept(instance);
        } catch (RuntimeException | Error ignore) {
            // best-effort teardown: an instance being thrown away must not mask the outcome already
            // being returned or thrown, and there is nothing left to retry against
        }
    }

    // ---- running one call ------------------------------------------------------------------------

    /**
     * Runs the call on its own thread and waits out the call limit. Abandoning it there is the only
     * way to bound it: a connector's read blocks the thread it runs on, so a limit on the caller's own
     * thread could only be checked after the read it was meant to bound had already returned.
     */
    private <R> R run(ConnectionConfig config, Lease lease, PooledCall<T, R> action) {
        Future<R> running;
        try {
            running = calls.submit(() -> action.run(lease.instance));
        } catch (RejectedExecutionException e) {
            throw new TapstateException(ConnectorError.READ_FAILED,
                    Map.of("connector", config.connectorId(), "detail", "the read face is shutting down"), e);
        }
        try {
            return running.get(limits.call().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            running.cancel(true);
            throw new Poisoned(new TapstateException(ConnectorError.READ_TIMEOUT,
                    Map.of("connector", config.connectorId(), "timeout", millis(limits.call())), e));
        } catch (InterruptedException e) {
            running.cancel(true);
            Thread.currentThread().interrupt();
            // The call is still running inside the instance, exactly as at the call limit, so this
            // leaves through the path that discards it rather than the one that pools it.
            throw new Poisoned(new TapstateException(ConnectorError.READ_FAILED,
                    Map.of("connector", config.connectorId(), "detail", "the read was interrupted"), e));
        } catch (ExecutionException e) {
            throw rethrow(config, e.getCause());
        }
    }

    /**
     * Re-raises what the call threw. A coded failure and an unchecked one carry their own diagnosis
     * and pass through unchanged; anything else is given the read-failure code so it does not reach a
     * user as a bare stack.
     */
    private static RuntimeException rethrow(ConnectionConfig config, Throwable cause) {
        if (cause instanceof RuntimeException unchecked) {
            throw unchecked;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return new TapstateException(ConnectorError.READ_FAILED,
                Map.of("connector", config.connectorId(), "detail", PdkSchemaDiscoverer.detail(cause)), cause);
    }

    private TapstateException busy(ConnectionConfig config, Throwable cause) {
        return new TapstateException(ConnectorError.INSTANCES_BUSY,
                Map.of("connector", config.connectorId(), "timeout", millis(limits.acquire())), cause);
    }

    private static String millis(Duration duration) {
        return duration.toMillis() + "ms";
    }

    // ---- state -----------------------------------------------------------------------------------

    /**
     * The pool abandoned a call that is still running inside its instance, so that instance must go.
     * A carrier rather than a code, because what reaches the caller is the code it wraps: whether an
     * instance is poisoned is the pool's own judgement, and cannot be read off the exception type the
     * call failed with — the read face reports a connector's own refusal as a code as well.
     */
    private static final class Poisoned extends RuntimeException {
        private final transient TapstateException coded;

        private Poisoned(TapstateException coded) {
            super(coded);
            this.coded = coded;
        }
    }

    /** One caller's exclusive hold on one instance. */
    private final class Lease {
        private final Slot slot;
        private final T instance;

        private Lease(Slot slot, T instance) {
            this.slot = slot;
            this.instance = instance;
        }
    }

    /** Everything filed under one key: whose turn it is, what is idle, and how many exist. */
    private final class Slot {
        private final Semaphore turns;
        private final Deque<Idle<T>> idle = new ArrayDeque<>();
        private int live;

        private Slot(int perConnection) {
            this.turns = new Semaphore(perConnection, true);
        }
    }

    /** A pooled instance nobody holds: when it went idle, and in what order it was returned. */
    private record Idle<T>(T instance, long idleSince, long returned) {
    }
}
