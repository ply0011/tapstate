package io.tapstate.cli;

/**
 * A sink for a followed collection: called once per change the server streams, oldest first. The CLI
 * mirrors the server's frame shape independently, the same way the two pipeline streams do.
 */
@FunctionalInterface
interface TailStream {

    /** One change: the row's identity, the row as the change carried it, and whether it was removed. */
    void change(TailChange change);
}
