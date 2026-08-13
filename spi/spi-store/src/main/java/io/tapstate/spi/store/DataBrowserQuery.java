package io.tapstate.spi.store;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One read of a collection: which collection, which rows, and how many at most. An immutable value
 * carrying no connector-framework types.
 *
 * <p>What it deliberately does <em>not</em> carry is as load-bearing as what it does. There is no
 * command field: the command a connector dispatches on is assembled by the drive and pinned to a
 * query, so no request spelling reaches a write. There is no database field either: which database a
 * read may touch follows from the connection alone, and a request that could name one would reach
 * databases the connection was never meant to read.
 *
 * <p>{@code filter} is held as an unmodifiable defensive copy; a null map is normalized to empty,
 * which reads every row. {@code limit} bounds one read. {@code sort} may be null, which asks for no
 * particular order and leaves it to the database — that is a request, not an omission, so it is not
 * normalized into some default.
 */
public record DataBrowserQuery(
        String collection, Map<String, Object> filter, DataBrowserSort sort, int limit) {

    public DataBrowserQuery {
        Objects.requireNonNull(collection, "collection");
        filter = filter == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(filter));
    }

    /** A read in the database's own order. */
    public DataBrowserQuery(String collection, Map<String, Object> filter, int limit) {
        this(collection, filter, null, limit);
    }
}
