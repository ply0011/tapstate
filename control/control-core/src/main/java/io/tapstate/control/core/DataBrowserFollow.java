package io.tapstate.control.core;

/**
 * A handle on a running follow, as a face holds it. Closing stops the stream and gives back the
 * connector instance behind it, along with its place in the host's instance ceiling; closing is
 * idempotent.
 *
 * <p>Whoever opened a follow owns closing it. One nobody closes does not fail — it keeps an instance
 * held and keeps counting, which surfaces later as somebody else being refused, with nothing to
 * connect the two.
 */
public interface DataBrowserFollow extends AutoCloseable {

    /** Stops the stream and releases what it holds. Idempotent. */
    @Override
    void close();
}
