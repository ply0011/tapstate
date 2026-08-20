package io.tapstate.spi.store;

import java.util.Objects;

/**
 * What a followed stream is asked for: one collection of the connection's own database, and nothing
 * else.
 *
 * <p>The omissions are the request, not gaps in it. There is no database, so a follow reaches only
 * what the connection already points at — the control plane's own tables sit on the same server. And
 * there is no filter: the read function behind this takes a table, not a query, so a filter here
 * would be a field the port could accept and then quietly fail to honour. Narrowing what a reader is
 * shown happens where the events are delivered, and what it narrows is the showing, never the
 * capture.
 */
public record DataBrowserTailRequest(String collection) {

    public DataBrowserTailRequest {
        Objects.requireNonNull(collection, "collection");
    }
}
