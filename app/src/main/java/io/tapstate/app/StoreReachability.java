package io.tapstate.app;

import io.tapstate.core.common.TapstateException;
import io.tapstate.spi.store.ConnectionConfig;
import io.tapstate.spi.store.ConnectionTestResult;
import io.tapstate.spi.store.ConnectionTester;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Answers whether the managed state store a view materializes into is actually answering, at the moment
 * a pipeline is started.
 *
 * <p>The check belongs here rather than at the first write because the alternative is a pipeline that
 * starts, reports itself running, and delivers nothing: the target is unreachable, so no row ever lands,
 * and the only signal is an absence. Starting is also when a person is present and watching, which is
 * what makes a refusal useful rather than merely correct.
 */
@FunctionalInterface
interface StoreReachability {

    /**
     * Throws a coded failure if the store under {@code storeSourceId} does not answer. {@code connectorId}
     * and {@code settings} are the store's own connection, as registered.
     */
    void requireReachable(String storeSourceId, String connectorId, Map<String, Object> settings);

    /** A check that asks nothing and refuses nothing -- the default where no prober is wired. */
    static StoreReachability assumingReachable() {
        return (storeSourceId, connectorId, settings) -> {
        };
    }

    /**
     * Probes the store by driving the connector's own connection test, treating anything but a pass as
     * unreachable.
     *
     * <p>A connector reporting FAILED and a connector throwing are the same answer to the only question
     * asked here -- can this pipeline write to it -- so both become the one coded failure, with what the
     * probe said carried in {@code reason} rather than discarded.
     */
    static StoreReachability probing(ConnectionTester tester, Duration timeout) {
        StoreReachability probe = (storeSourceId, connectorId, settings) -> {
            ConnectionTestResult result =
                    tester.test(new ConnectionConfig(storeSourceId, connectorId, settings));
            if (result.outcome() != ConnectionTestResult.Outcome.PASSED) {
                throw unreachable(storeSourceId, describe(result));
            }
        };
        return bounded(probe, timeout);
    }

    /**
     * Wraps a check so it gives up after {@code timeout} instead of waiting on it.
     *
     * <p>A connector's connection test carries no deadline of its own, so a store that accepts the
     * connection and then goes quiet would park the start indefinitely -- and an indefinite start is the
     * failure this task exists to remove, not a lesser form of it. The probe runs on its own thread and is
     * interrupted on expiry; a connector that ignores the interrupt leaves that thread parked, which is
     * accepted here because the alternative is parking the caller as well.
     */
    static StoreReachability bounded(StoreReachability probe, Duration timeout) {
        return (storeSourceId, connectorId, settings) -> {
            CompletableFuture<Void> answer = CompletableFuture.runAsync(
                    () -> probe.requireReachable(storeSourceId, connectorId, settings));
            try {
                answer.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                answer.cancel(true);
                throw unreachable(storeSourceId,
                        "no answer within " + timeout.toMillis() + "ms");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw unreachable(storeSourceId, "interrupted while probing");
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                // A coded refusal from the probe is the answer, not a wrapper to re-describe.
                if (cause instanceof TapstateException coded) {
                    throw coded;
                }
                throw unreachable(storeSourceId, String.valueOf(cause));
            }
        };
    }

    private static TapstateException unreachable(String storeSourceId, String reason) {
        return new TapstateException(ActuationError.VIEW_STORE_UNREACHABLE,
                Map.of("store", storeSourceId, "reason", reason), null);
    }

    /** What the probe reported, in one line, for the operator to act on. */
    private static String describe(ConnectionTestResult result) {
        StringBuilder said = new StringBuilder();
        result.items().forEach(item -> {
            if (said.length() > 0) {
                said.append("; ");
            }
            said.append(item.name()).append(": ").append(item.message());
        });
        return said.length() == 0 ? "the connector reported no passing check" : said.toString();
    }
}
