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
 * <p>Four operations, one port, because an implementation holds live state across all of them — a
 * connector instance is expensive to open and is pooled, not opened per read. That is also why the
 * port closes: {@link #close()} hands back everything the reads are holding. Splitting it into four
 * ports would either multiply that state or leave its lifecycle owned by nothing.
 *
 * <p>{@link #tail} is the one that does not fit the pooled shape and belongs here anyway. It holds a
 * connector for as long as somebody is watching rather than for the length of one call, so it cannot
 * take a pooled instance — but it is still an instance, still multiplying out to driver connections,
 * and it has to count against the same host-wide ceiling as the reads. Only the implementation that
 * owns that ceiling can make it count.
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
     * Follows one collection's changes, delivering each to {@code listener} until the returned
     * subscription is closed. The stream starts at the moment it is opened: a follow shows what
     * happens next, and replaying history is a different request that this one must not silently
     * become.
     *
     * <p>Unlike the reads, this holds a connector instance for the life of the subscription. It
     * counts against the same host-wide ceiling they do, so a host with every instance spoken for
     * refuses a new follow with a code rather than opening one more.
     */
    DataBrowserSubscription tail(
            ConnectionConfig config, DataBrowserTailRequest request, DataBrowserChangeListener listener);

    /**
     * Releases everything the reads hold. Narrowed from the inherited signature: a reader that cannot
     * be shut down without handling a checked failure would push that handling into the assembly root,
     * which has nothing useful to do with it.
     */
    @Override
    void close();
}
