package io.tapstate.control.core;

import io.tapstate.spi.store.DataBrowserSort;

import java.util.Objects;

/**
 * The order one read is asked to come back in, in the control ring's own vocabulary: a single field and a
 * direction. The surfaces send this; the service translates it into the storage-port request, so no face
 * reaches into the ports to express an order.
 *
 * <p>Deliberately one field rather than a list. A read face that sorts on several fields is expressing a
 * query, and this face returns a preview; the narrowing is the same stance the filter vocabulary takes.
 * {@code field} may be a dot path, because the documents being previewed are nested.
 *
 * <p>Absent — a null order on the request — is not the same as any value here. It means the caller asked
 * for no particular order, which leaves the order to the database and imposes nothing.
 */
public record DataBrowserSortOrder(String field, Direction direction) {

    public DataBrowserSortOrder {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(direction, "direction");
    }

    /** Which way the field orders. */
    public enum Direction {
        ASC,
        DESC
    }

    /** The storage-port request for this order. */
    DataBrowserSort toPortRequest() {
        return new DataBrowserSort(field, switch (direction) {
            case ASC -> DataBrowserSort.Direction.ASC;
            case DESC -> DataBrowserSort.Direction.DESC;
        });
    }
}
