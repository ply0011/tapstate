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
 *
 * <p>A row is laid out by the same rule the in-place view uses ({@link NestedLayout}): one that fits
 * stays on its line, one that does not is opened up a member per line. Two differences, both
 * deliberate. It elides nothing and cuts nothing -- this view has no frame to fit, so a row arrives
 * whole however long it is. And a row small enough to read on one line keeps that line, which is what
 * stops a stream of small changes from becoming a wall.
 *
 * <p>The cost is named because it is real: a row that does open up no longer occupies one line, so a
 * grep over this stream matches the leaf rather than the row holding it. The event line still opens at
 * column zero, which is what keeps the stream scannable for where a change starts.
 */
final class TailRenderer {

    /** The indent under an event line, wide enough to clear the time column. */
    private static final String DETAIL_INDENT = "          ";

    /** Where a row's own lines sit: past the indent and past the side label that opens the first one. */
    private static final String ROW_INDENT = DETAIL_INDENT + "        ";

    private TailRenderer() {
    }

    /** The lines one change produces: the event, then whichever rows it carried. */
    static List<String> lines(TailChange change, int width) {
        List<String> lines = new ArrayList<>();
        lines.add(change.at() + "  " + change.kind().name().toLowerCase(Locale.ROOT));
        row(lines, "before", change.before(), width);
        row(lines, "after ", change.after(), width);
        return lines;
    }

    /**
     * One side of a change, or nothing at all when it was not carried. Nothing rather than an empty
     * object: a row the connector did not supply and a row it supplied as empty are different facts,
     * and only one of them is usually true.
     */
    private static void row(List<String> lines, String side, Map<String, Object> row, int width) {
        if (row == null) {
            return;
        }
        List<String> laid = NestedLayout.lines(row, Math.max(width - ROW_INDENT.length(), 1), false);
        lines.add(DETAIL_INDENT + side + "  " + laid.get(0));
        for (int i = 1; i < laid.size(); i++) {
            lines.add(ROW_INDENT + laid.get(i));
        }
    }
}
