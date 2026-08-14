package io.tapstate.spi.store;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One change a followed collection reported.
 *
 * <p>Two kinds rather than the three a store writes. Whether a write created a row or altered one is
 * a distinction a change stream does not reliably make and a reader cannot act on: an alteration to a
 * row this stream has not seen before and a creation are the same event to anything downstream. A
 * removal is different in kind — there is no row afterwards.
 *
 * @param key the row's identity, as the connector's own declared primary key gives it. Empty when
 *            the collection declares none, which is a real answer: a stream of a keyless collection
 *            cannot say that two changes concern the same row.
 * @param row the row as the change carried it, which for an alteration may hold only the fields the
 *            write touched. Null for a removal.
 * @param at  when the store made the change, as epoch milliseconds.
 */
public record DataBrowserChange(Kind kind, String key, Map<String, Object> row, long at) {

    /** What happened to the row. */
    public enum Kind {

        /** The row now exists, or exists differently. */
        UPSERT,

        /** The row is gone. */
        DELETE
    }

    public DataBrowserChange {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(key, "key");
        row = row == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(row));
    }
}
