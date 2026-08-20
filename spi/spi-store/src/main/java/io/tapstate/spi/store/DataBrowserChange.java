package io.tapstate.spi.store;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One change a followed collection reported: what the store did, the row on either side of it as far
 * as the connector supplied them, and when.
 *
 * <p>Four slots and no derived ones. A change stream says what a store did, and how completely it
 * says it is the connector's business: an alteration may arrive with the row as it was, or only as it
 * now is; a removal may arrive with the whole row or with nothing but its key. Filling any of that in
 * — by remembering earlier versions, by reading the row back, by working out an identity — would hand
 * the reader an answer that looks like the stream's and is not, which they have no way to tell apart.
 *
 * @param before the row as it was, when the connector supplied it; null means not supplied, which is
 *               a different fact from an empty row
 * @param after  the row as it now is; null for a removal
 * @param at     when the store made the change, as epoch milliseconds
 */
public record DataBrowserChange(Kind kind, Map<String, Object> before, Map<String, Object> after, long at) {

    /** What the store did, kept as the three it distinguishes rather than folded into fewer. */
    public enum Kind {
        INSERT,
        UPDATE,
        DELETE
    }

    public DataBrowserChange {
        Objects.requireNonNull(kind, "kind");
        before = copyOrNull(before);
        after = copyOrNull(after);
    }

    private static Map<String, Object> copyOrNull(Map<String, Object> row) {
        return row == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(row));
    }

    /** The row a filter is tested against: what it now is, or what it was when that is all there is. */
    public Map<String, Object> subject() {
        return after != null ? after : before;
    }
}
