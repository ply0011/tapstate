package io.tapstate.control.core;

import io.tapstate.spi.store.DataBrowserChange;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One change to a followed collection, in the control ring's own words: what the store did, the row
 * on either side of it as far as the connector supplied them, and when.
 *
 * <p>It carries no more than the port handed over. What a change stream says about itself is the
 * connector's business — an alteration may arrive with the row as it was or only as it now is, a
 * removal with the whole row or with nothing but its key — and a layer that filled any of that in
 * would be showing the reader something the store never said.
 */
public record DataBrowserChangeEvent(
        Kind kind, Map<String, Object> before, Map<String, Object> after, long at) {

    /** What the store did. */
    public enum Kind {
        INSERT,
        UPDATE,
        DELETE
    }

    public DataBrowserChangeEvent {
        before = copyOrNull(before);
        after = copyOrNull(after);
    }

    private static Map<String, Object> copyOrNull(Map<String, Object> row) {
        return row == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(row));
    }

    /** The control-ring event for a port-level change. */
    static DataBrowserChangeEvent from(DataBrowserChange change) {
        return new DataBrowserChangeEvent(
                Kind.valueOf(change.kind().name()), change.before(), change.after(), change.at());
    }
}
