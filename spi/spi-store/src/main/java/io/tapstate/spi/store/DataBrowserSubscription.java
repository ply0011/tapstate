package io.tapstate.spi.store;

/**
 * A handle on a running follow. Closing it stops the stream and gives back everything it holds — a
 * connector instance, its driver connections, and its place in the host-wide instance ceiling.
 * Closing is idempotent.
 *
 * <p>Whoever starts a follow owns closing it. A stream nobody closes does not fail; it keeps a
 * connector open and keeps counting against the ceiling, which is the shape of leak that shows up
 * later as somebody else being refused.
 */
public interface DataBrowserSubscription extends AutoCloseable {

    /** Stops the stream and releases what it holds. Idempotent. */
    @Override
    void close();
}
