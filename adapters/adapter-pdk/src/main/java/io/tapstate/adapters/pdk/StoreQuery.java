package io.tapstate.adapters.pdk;

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
 * which reads every row. {@code limit} bounds one read.
 */
public record StoreQuery(String collection, Map<String, Object> filter, int limit) {

    public StoreQuery {
        Objects.requireNonNull(collection, "collection");
        filter = filter == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(filter));
    }
}
