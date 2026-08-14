package io.tapstate.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders the in-place view: one row, redrawn whole whenever it changes, with a mark beside each field
 * that moved. It renders from a {@link DocumentDiff} rather than comparing rows itself, so it and the
 * appended view can never come to disagree about what changed.
 *
 * <p>Two of its rules are load-bearing rather than cosmetic. A frame that found nothing new renders as
 * nothing at all: redrawing an identical screen every second tells the reader nothing, and it also
 * keeps the byte stream from ever going idle. And the footer states, in words, the two things the view
 * cannot promise — the row is the database's first in its own order, which is neither a stable pick
 * nor the newest — because a reader has no way to discover either from the screen.
 */
final class WatchRenderer {

    /** How wide a field name is padded to before its value, so a column of values lines up. */
    private static final int FIELD_WIDTH = 16;

    /** The escape byte that opens every cursor-control sequence. */
    private static final String ESC = "\u001b";

    private WatchRenderer() {
    }

    /**
     * One frame: a header saying what is being watched and how, the row with a mark beside each changed
     * field, and the footer. Empty when nothing changed — the caller writes nothing rather than
     * redrawing the same screen.
     *
     * @param approximateTotal how many rows the collection holds, or null when the read was filtered
     *                         and the count was therefore not paid for. Null renders as no count at
     *                         all, never as zero.
     */
    static List<String> frame(String namespace, Map<String, Object> row, DocumentDiff diff,
            Long approximateTotal) {
        if (diff.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add("watching " + namespace + " · one row · polling every 1s");
        for (String field : diff.fields()) {
            DocumentChange change = diff.change(field);
            char slot = change == null ? ' ' : change.mark().slot();
            // A departed field is shown with the value it last had: the reader is being told it went
            // away, and an empty line would leave them unsure what went away.
            Object value = change != null && change.mark() == DocumentChange.Mark.REMOVED
                    ? change.before() : row.get(field);
            lines.add(slot + " " + pad(field) + JsonOut.compact(value));
        }
        lines.add(footer(approximateTotal));
        return lines;
    }

    /**
     * The line under the row, and the only place the view says what it is not. It states that one row
     * is being shown out of however many there are; that the row is the first in the database's own
     * order, which two identical reads may answer differently and which is not the newest row; and
     * that the whole table has its own command, since this view will never show a change to any other
     * row.
     */
    static String footer(Long approximateTotal) {
        return "showing 1"
                + (approximateTotal == null ? "" : " of ~" + approximateTotal)
                + " (first in natural order — not stable, not the newest)"
                + " · use `tail` for the whole table";
    }

    /**
     * The line that keeps updating while the row does not, on its own slower cadence. It carries both
     * halves deliberately: when the row last changed, and when the view last looked. With only the
     * first, a reader cannot tell a row that is quiet from a view that has died — the same shape of
     * silent failure as a truncated read that passes for a small collection.
     */
    static String status(String lastChange, String lastSummary, String checked) {
        String change = lastChange == null
                ? "no change yet"
                : "last change " + lastChange + (lastSummary == null ? "" : " (" + lastSummary + ")");
        return change + " · checked " + checked;
    }

    /**
     * The cursor movement that puts the next frame where the last one was: up over the lines already
     * written, then clear from there down. Empty when nothing has been written yet, so the first frame
     * lands wherever the prompt left off instead of eating the scrollback above it.
     *
     * <p>This is the whole of "in place". It is also why the view refuses to run without a terminal:
     * down a pipe these are not a degraded redraw, they are bytes in the middle of the data.
     */
    static String redrawOver(int previousLines) {
        return previousLines <= 0 ? "" : ESC + "[" + previousLines + "A" + ESC + "[0J";
    }

    private static String pad(String field) {
        return field.length() >= FIELD_WIDTH ? field + "  " : (field + " ".repeat(FIELD_WIDTH - field.length()));
    }
}
