package io.tapstate.e2e;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A hold that can be put on one store's traffic, sitting between that store and whatever reads it.
 *
 * <p>This exists because a pipeline is suspended whole. A job runs or it does not, so no product verb
 * can mean "this one source stops for a moment while the others carry on" - and that arrangement is
 * exactly what a specification about ordering needs, because it is the only way to make rows arrive in
 * an order that disagrees with the order their source recorded them in. Waiting cannot produce it: a
 * wait makes one stream late, and late is what the source order already says.
 *
 * <p>So the hold is put where this harness owns the ground - a relay in front of the store, whose
 * address is the one published to the product. Held, nothing crosses toward the reader; the store keeps
 * accepting writes the whole time, records them in its own log, and hands them over in that order when
 * the hold lifts. Nothing is dropped and nothing is reordered by the gate itself, which is what makes
 * the resulting arrival order a statement about the product rather than about this class.
 *
 * <p><b>Only the store-to-reader direction is held.</b> The other way keeps flowing, so the reader's
 * acknowledgements and keepalives still arrive and the store never concludes it is talking to a dead
 * client - a connection torn down and re-established would be a different scenario (a reconnect, with
 * its own recovery) and would prove something this is not asking about.
 *
 * <p>Holding is a state rather than a count: holding a held gate is nothing, releasing an open one is
 * nothing. A gate still held at {@link #close} is released as it closes, so a run that forgot cannot
 * leave a relay thread parked forever.
 */
final class StreamGate implements AutoCloseable {

    /** Copy buffer. Large enough that a snapshot does not cross in thousands of hops, small enough to be cheap. */
    private static final int BUFFER = 32 * 1024;

    private final String targetHost;
    private final int targetPort;
    private final ServerSocket listener;
    private final Thread accepting;
    private final List<Socket> open = new CopyOnWriteArrayList<>();

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition released = lock.newCondition();
    private boolean held;
    private volatile boolean closed;

    private StreamGate(String targetHost, int targetPort, ServerSocket listener) {
        this.targetHost = targetHost;
        this.targetPort = targetPort;
        this.listener = listener;
        this.accepting = new Thread(this::accept, "stream-gate-" + listener.getLocalPort());
        this.accepting.setDaemon(true);
        this.accepting.start();
    }

    /** Opens a gate in front of the given address, listening on a loopback port of the system's choosing. */
    static StreamGate inFrontOf(String targetHost, int targetPort) {
        try {
            ServerSocket listener = new ServerSocket();
            listener.setReuseAddress(true);
            listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
            return new StreamGate(targetHost, targetPort, listener);
        } catch (IOException cannotListen) {
            throw new IllegalStateException(
                    "cannot open a stream gate in front of " + targetHost + ":" + targetPort, cannotListen);
        }
    }

    /** The host a reader dials to reach the store through this gate. */
    String host() {
        return listener.getInetAddress().getHostAddress();
    }

    /** The port a reader dials to reach the store through this gate. */
    int port() {
        return listener.getLocalPort();
    }

    /** Stops anything crossing toward the reader. Already held is already right. */
    void hold() {
        lock.lock();
        try {
            held = true;
        } finally {
            lock.unlock();
        }
    }

    /** Lets everything the store recorded meanwhile through, in its order. Already open is already right. */
    void release() {
        lock.lock();
        try {
            held = false;
            released.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** True while the gate is holding, for a reading that wants to say which state it observed. */
    boolean isHeld() {
        lock.lock();
        try {
            return held;
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        closed = true;
        release();
        try {
            listener.close();
        } catch (IOException alreadyGone) {
            // Closing a listener that is already down is the state this wanted.
        }
        open.forEach(StreamGate::closeQuietly);
        open.clear();
        accepting.interrupt();
    }

    private void accept() {
        while (!closed) {
            Socket reader = null;
            try {
                reader = listener.accept();
                open.add(reader);
                // Dialled after the accept, so it can fail on its own - a store that is down, or a
                // connection limit reached. That failure belongs to this one connection: it closes the
                // socket just accepted and the loop goes back to accepting, because a relay that
                // stopped accepting here would leave every later connection hanging on a listener
                // nobody is reading, which reads from above as the product having gone quiet.
                Socket store = new Socket(targetHost, targetPort);
                open.add(store);
                // Toward the reader: the direction the hold applies to.
                pump(store, reader, true);
                // Toward the store: never held, so acknowledgements keep arriving.
                pump(reader, store, false);
            } catch (IOException failed) {
                if (closed) {
                    return;
                }
                if (reader == null) {
                    // The listener itself, not one connection: there is nothing left to accept on.
                    throw new IllegalStateException("stream gate stopped accepting", failed);
                }
                open.remove(reader);
                closeQuietly(reader);
            }
        }
    }

    private void pump(Socket from, Socket to, boolean holdable) {
        Thread pumping = new Thread(() -> copy(from, to, holdable), "stream-gate-pump");
        pumping.setDaemon(true);
        pumping.start();
    }

    /**
     * Copies one direction until the far end goes away.
     *
     * <p>The hold is taken twice per hop, and both matter. Before the read, so a held gate stops
     * draining the store and the store's own log - not this process - is what retains the backlog.
     * Before the write, so that a chunk already in hand when the hold went on does not slip across:
     * without it, a specification that holds and then asserts would race that one buffer.
     */
    private void copy(Socket from, Socket to, boolean holdable) {
        byte[] buffer = new byte[BUFFER];
        try (InputStream in = from.getInputStream(); OutputStream out = to.getOutputStream()) {
            while (!closed) {
                if (holdable) {
                    awaitRelease();
                }
                int read = in.read(buffer);
                if (read < 0) {
                    return;
                }
                if (holdable) {
                    awaitRelease();
                }
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException farEndWentAway) {
            // A closed connection is how a relay learns the conversation is over.
        } catch (InterruptedException closing) {
            Thread.currentThread().interrupt();
        } finally {
            closeQuietly(from);
            closeQuietly(to);
        }
    }

    private void awaitRelease() throws InterruptedException {
        lock.lock();
        try {
            while (held && !closed) {
                released.await();
            }
        } finally {
            lock.unlock();
        }
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException alreadyGone) {
            // Same state either way.
        }
    }
}
