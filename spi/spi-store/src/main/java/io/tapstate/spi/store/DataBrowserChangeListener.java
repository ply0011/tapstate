package io.tapstate.spi.store;

/**
 * Where a followed collection's changes are delivered. Called once per change, on the stream's own
 * thread, in the order the store made them.
 */
@FunctionalInterface
public interface DataBrowserChangeListener {

    /** One change the collection reported. */
    void onChange(DataBrowserChange change);

    /**
     * Called when the stream fails, so a failure a background stream cannot return to its caller still
     * reaches it. Delivered at most once, after which the stream has ended. The default ignores it:
     * a listener that does not watch stream health is a listener that will be told by its own silence.
     */
    default void onError(Throwable error) {
    }
}
