package io.tapstate.control.core;

import io.tapstate.spi.store.DataBrowserChange;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One change to a followed collection, in the control ring's own words.
 *
 * <p>It exists for the same reason the read reports do: what crosses out to a face is this ring's
 * vocabulary, never a storage port's. A face that had to name a port type to render a streamed row
 * would be a face reaching past the service, and the layering gate says so out loud.
 *
 * @param row the row as the change carried it, which for an alteration may hold only the fields the
 *            write touched. Null when the row was removed.
 */
public record DataBrowserChangeEvent(boolean removed, String key, Map<String, Object> row, long at) {

    public DataBrowserChangeEvent {
        row = row == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(row));
    }

    /** The control-ring event for a port-level change. */
    static DataBrowserChangeEvent from(DataBrowserChange change) {
        return new DataBrowserChangeEvent(
                change.kind() == DataBrowserChange.Kind.DELETE, change.key(), change.row(), change.at());
    }
}
