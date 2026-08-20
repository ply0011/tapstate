package io.tapstate.control.core;

import io.tapstate.core.common.TapstateErrorCode;

/**
 * Where a followed collection's changes are handed to a face. Called once per change that the
 * follow's filter admits, on the stream's own thread, in the order the store made them.
 */
@FunctionalInterface
public interface DataBrowserChangeSink {

    /** One change the reader should see. */
    void onChange(DataBrowserChangeEvent change);

    /**
     * Called at most once when the follow ends by itself rather than because the reader let go, naming
     * why. A follow runs on a thread of its own, so an ending has nowhere to be returned to; a face
     * that does not take this is a face whose reader is told by silence, and silence here reads as a
     * collection nobody is changing.
     *
     * <p>The default does nothing, for a caller that only wants the changes.
     */
    default void onEnded(TapstateErrorCode reason) {
    }
}
