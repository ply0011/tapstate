package io.tapstate.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders the appended view: one event per change the store made, oldest first, never redrawn.
 *
 * <p>It shows what arrived and works nothing out. A view that compared each change against an earlier
 * one could say more — which field moved, what it moved from — but only by keeping its own history
 * and presenting it as the store's, and a reader would have no way to tell whose it was. What is on
 * screen here is exactly what the connector supplied: both rows when a change carries both, one when
 * it carries one, and the kind and the time always.
 *
 * <p>Every event opens at column zero with its time and kind, so the stream stays something a reader
 * can pipe into a search; the rows are indented under it, told apart by position rather than by
 * punctuation a grep would have to know about.
 */
final class TailRenderer {

    /** The indent under an event line, wide enough to clear the time column. */
    private static final String DETAIL_INDENT = "          ";

    private TailRenderer() {
    }

    /** The lines one change produces: the event, then whichever rows it carried. */
    static List<String> lines(TailChange change) {
        List<String> lines = new ArrayList<>();
        lines.add(change.at() + "  " + change.kind().name().toLowerCase(Locale.ROOT));
        row(lines, "before", change.before());
        row(lines, "after ", change.after());
        return lines;
    }

    /**
     * One side of a change, or nothing at all when it was not carried. Nothing rather than an empty
     * object: a row the connector did not supply and a row it supplied as empty are different facts,
     * and only one of them is usually true.
     */
    private static void row(List<String> lines, String side, Map<String, Object> row) {
        if (row != null) {
            lines.add(DETAIL_INDENT + side + "  " + JsonOut.compact(row));
        }
    }
}
