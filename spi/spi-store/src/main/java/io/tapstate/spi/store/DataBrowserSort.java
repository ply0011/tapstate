package io.tapstate.spi.store;

import java.util.Objects;

/**
 * The order one read is returned in: a single field and a direction. Neutral by construction — it names
 * no backend's encoding, so the bridge driving a particular connector is what turns it into whatever
 * that connector's query expects.
 *
 * <p>Deliberately one field rather than a list. A read face that sorts on several fields is expressing a
 * query, and this face returns a preview; the narrowing is the same stance the filter vocabulary takes.
 * {@code field} may be a dot path, because the documents being previewed are nested.
 *
 * <p>Absent — a null sort on the request — is not the same as any value here. It means the caller asked
 * for no particular order, which leaves the order to the database and imposes nothing.
 */
public record DataBrowserSort(String field, Direction direction) {

    public DataBrowserSort {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(direction, "direction");
    }

    /** Which way the field orders. */
    public enum Direction {
        ASC,
        DESC
    }
}
