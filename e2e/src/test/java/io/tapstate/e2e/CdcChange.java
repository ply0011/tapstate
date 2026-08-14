package io.tapstate.e2e;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One change a specification asks for by name, against a row it names.
 *
 * <p>The counted form of a {@code cdc} step - "insert 10" - says how many rows change, never which or
 * to what. That is enough while an assertion counts rows, and not enough the moment one reads a value:
 * a case that changes a child row and expects the parent document to follow has to be able to say which
 * child row and what value, or it can assert an outcome it has no way to produce.
 *
 * <p>Locating a row is spelled {@code where} and holding it to values is spelled by the columns
 * themselves, the same two shapes the {@code doc} matcher already reads on the assertion side. A change
 * and the assertion that checks it therefore address a row the same way, which is what keeps a case
 * readable as one sentence rather than two dialects.
 *
 * <p>Values are whatever the specification wrote, passed to the driver unread. What a store makes of
 * them is the store's own affair - a driver that cannot hold a value says so in its own words rather
 * than having the vocabulary guess a type on its behalf.
 */
public sealed interface CdcChange {

    /** Lays down one row spelled out column by column. */
    record Insert(Map<String, Object> row) implements CdcChange {
        public Insert {
            row = unmodifiable(row);
        }
    }

    /** Changes the columns in {@code set} on the rows {@code where} locates. */
    record Update(Map<String, Object> where, Map<String, Object> set) implements CdcChange {
        public Update {
            where = unmodifiable(where);
            set = unmodifiable(set);
        }
    }

    /** Takes away the rows {@code where} locates. */
    record Delete(Map<String, Object> where) implements CdcChange {
        public Delete {
            where = unmodifiable(where);
        }
    }

    private static Map<String, Object> unmodifiable(Map<String, Object> values) {
        // Not Map.copyOf: a column written with no value parses to null, and copyOf answers that with a
        // bare NullPointerException instead of naming the column that carried it.
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
