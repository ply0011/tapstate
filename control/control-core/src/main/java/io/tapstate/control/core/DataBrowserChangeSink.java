package io.tapstate.control.core;

/**
 * Where a followed collection's changes are handed to a face. Called once per change that the
 * follow's filter admits, on the stream's own thread, in the order the store made them.
 */
@FunctionalInterface
public interface DataBrowserChangeSink {

    /** One change the reader should see. */
    void onChange(DataBrowserChangeEvent change);
}
