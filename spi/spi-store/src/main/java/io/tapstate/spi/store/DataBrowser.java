package io.tapstate.spi.store;

import java.util.List;

/**
 * Reads what a stored connection's own database holds: the collections it exposes, what one of them
 * reports about its size, and the rows one query matches. The port carries no connector-framework
 * types — a request is a value, a result is a value.
 *
 * <p>Which database a read reaches follows from the connection alone. No method takes one, and the
 * query request has no field for one, so a caller cannot name a database the connection was never
 * meant to read. That is the read face's load-bearing confinement, not a convenience: the control
 * plane's own database sits on the same server as the data.
 *
 * <p>Three operations, one port, because an implementation holds live state across all three — a
 * connector instance is expensive to open and is pooled, not opened per read. That is also why the
 * port closes: {@link #close()} hands back everything the reads are holding. Splitting it into three
 * ports would either triple that state or leave its lifecycle owned by nothing.
 *
 * <p>An {@link ExecutionPort}: it drives a connector, so it runs on the runtime side and control
 * reaches it only through the whitelisted probe seam — one probe per operation.
 */
public interface DataBrowser extends ExecutionPort, AutoCloseable {

    /** Lists the collections the connection's own database holds, in the order the connector reports them. */
    List<String> collections(ConnectionConfig config);

    /** Reports what the connector knows about one collection's size. */
    DataBrowserTableInfo stats(ConnectionConfig config, String collection);

    /**
     * Runs {@code query} against the connection's own database and returns the rows it matched, along
     * with what could be told cheaply about how much was left behind. A read is bounded, so answering
     * with the rows alone would leave a caller unable to tell a small collection from the start of a
     * large one.
     */
    DataBrowserPreview find(ConnectionConfig config, DataBrowserQuery query);

    /**
     * Releases everything the reads hold. Narrowed from the inherited signature: a reader that cannot
     * be shut down without handling a checked failure would push that handling into the assembly root,
     * which has nothing useful to do with it.
     */
    @Override
    void close();
}
