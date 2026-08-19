package io.tapstate.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Renders the in-place view: one row as a bordered table of two versions side by side — what the row
 * holds now, and what it held one version ago. A new version enters on the left and pushes the one it
 * replaced to the right, so the right column is always exactly one step behind and never the version
 * the view started on.
 *
 * <p>Every field appears in both columns, whether or not it moved. Showing only the fields that
 * changed would make the reader work out which of two shapes they are looking at before they can read
 * either; a full pair of versions is the same picture every frame.
 *
 * <p>Three of its rules are load-bearing rather than cosmetic. A frame is always the whole screen, so
 * a caller that erases what it last drew can never erase more than it is about to replace. The footer
 * states, in words, the two things the view cannot promise — the row is the database's first in its
 * own order, which is neither a stable pick nor the newest — because a reader has no way to discover
 * either from the screen. And a screen too narrow for two columns drops the older one rather than
 * wrapping: a table that wraps is harder to read than a table with one column less.
 */
final class WatchRenderer {

    /** The escape byte that opens every cursor-control sequence. */
    private static final String ESC = "\u001b";

    /** Narrowest a value column is allowed to get before the older version is dropped instead. */
    private static final int MIN_VALUE_WIDTH = 8;

    /** Bounds on the field-name column, so one long name cannot crowd out both values. */
    private static final int MIN_FIELD_WIDTH = 8;
    private static final int MAX_FIELD_WIDTH = 28;

    /** What a line spends on borders and padding, with two value columns and with one. */
    private static final int TWO_COLUMN_CHROME = 10;
    private static final int ONE_COLUMN_CHROME = 7;

    private WatchRenderer() {
    }

    /**
     * One frame: the row as a bordered table, titled with what is being watched. Always the complete
     * table — a caller redrawing in place erases exactly what it drew last time, so a frame that left
     * something out would take the rest of the row off the screen with it.
     *
     * @param was   the version one step back, or null before anything has changed. Null renders an
     *              empty column rather than no column, so the table does not change shape under the
     *              reader when the first change lands.
     * @param diff  supplies the field order and which fields moved. Marks are only drawn when there is
     *              a version for them to have moved from.
     * @param width how wide the screen is; the table is drawn no wider, and drops the older column
     *              rather than overflow it.
     */
    static List<String> frame(String namespace, Map<String, Object> now, Map<String, Object> was,
            DocumentDiff diff, Long approximateTotal, int width) {
        List<String[]> rows = new ArrayList<>();
        for (String field : diff.fields()) {
            DocumentChange change = diff.change(field);
            boolean gone = change != null && change.mark() == DocumentChange.Mark.REMOVED;
            char slot = was == null || change == null ? ' ' : change.mark().slot();
            // A departed field keeps its place with the value it last had on the right and nothing on
            // the left: that pair is the reader being told what went away, which an omitted row is not.
            String nowCell = gone ? "" : JsonOut.compact(now.get(field));
            String wasCell = was == null ? "" : JsonOut.compact(was.get(field));
            rows.add(new String[] {slot + " " + field, nowCell, wasCell});
        }
        return table("watching " + namespace + " · one row · polling every 1s", rows, width,
                approximateTotal);
    }

    private static List<String> table(String title, List<String[]> rows, int width,
            Long approximateTotal) {
        int fieldWidth = bound(widest(rows, 0), MIN_FIELD_WIDTH, MAX_FIELD_WIDTH);
        boolean bothColumns = width - TWO_COLUMN_CHROME - fieldWidth >= MIN_VALUE_WIDTH * 2;
        int chrome = bothColumns ? TWO_COLUMN_CHROME : ONE_COLUMN_CHROME;

        // As wide as the content wants, but never narrower than the title -- the title is the only
        // place the view says it polls, and a reader who takes it for a push reads a stale row as live.
        // Both value columns are sized by the widest value in either of them, so an empty was column
        // is already the size of what will land in it. Sizing it to its own content instead makes the
        // whole table jump wider the moment the first change arrives -- under a reader who was told
        // this view redraws one row in place.
        int valueNatural = Math.max(Math.max(widest(rows, 1), widest(rows, 2)), MIN_VALUE_WIDTH);
        int naturalValues = bothColumns ? valueNatural * 2 : valueNatural;
        int target = Math.min(width, Math.max(fieldWidth + naturalValues + chrome, title.length() + 5));
        int forValues = Math.max(target - chrome - fieldWidth, MIN_VALUE_WIDTH * (bothColumns ? 2 : 1));

        List<Integer> columns = bothColumns
                ? List.of(fieldWidth, forValues - forValues / 2, forValues / 2)
                : List.of(fieldWidth, forValues);

        List<String> lines = new ArrayList<>();
        lines.add(titledTop(title, columns));
        lines.add(row(bothColumns
                ? new String[] {"field", "now", "was"}
                : new String[] {"field", "now"}, columns));
        lines.add(rule("├", "┼", "┤", columns));
        for (String[] cells : rows) {
            lines.add(row(bothColumns ? cells : new String[] {cells[0], cells[1]}, columns));
        }
        lines.add(rule("└", "┴", "┘", columns));
        // Wrapped rather than left to the terminal: the caller redraws by moving the cursor up as many
        // lines as it wrote, and a line the terminal wraps for it occupies two rows it never counted.
        lines.addAll(wrap(footer(approximateTotal), width));
        return lines;
    }

    /** Breaks a sentence on spaces so no line is wider than the screen the caller is counting rows on. */
    static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            if (!line.isEmpty() && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                line.setLength(0);
            }
            line.append(line.isEmpty() ? "" : " ").append(word);
        }
        lines.add(line.toString());
        return lines;
    }

    /** The top border with the title set into it, so the title costs no line of its own. */
    private static String titledTop(String title, List<Integer> columns) {
        int inner = inner(columns);
        String label = " " + fit(title, Math.max(inner - 3, 1)) + " ";
        String tail = "─".repeat(Math.max(inner - 1 - label.length(), 0));
        return "┌─" + label + tail + "┐";
    }

    private static String rule(String left, String join, String right, List<Integer> columns) {
        StringBuilder line = new StringBuilder(left);
        for (int i = 0; i < columns.size(); i++) {
            line.append("─".repeat(columns.get(i) + 2));
            line.append(i == columns.size() - 1 ? right : join);
        }
        return line.toString();
    }

    private static String row(String[] cells, List<Integer> columns) {
        StringBuilder line = new StringBuilder("│");
        for (int i = 0; i < columns.size(); i++) {
            int width = columns.get(i);
            String cell = fit(i < cells.length ? cells[i] : "", width);
            line.append(' ').append(cell).append(" ".repeat(width - cell.length())).append(" │");
        }
        return line.toString();
    }

    /** How wide the inside of the box is: every column plus the padding and rules between them. */
    private static int inner(List<Integer> columns) {
        int total = 0;
        for (int width : columns) {
            total += width + 3;
        }
        return total - 1;
    }

    private static int widest(List<String[]> rows, int column) {
        int widest = 0;
        for (String[] cells : rows) {
            widest = Math.max(widest, cells[column].length());
        }
        return widest;
    }

    private static int bound(int value, int low, int high) {
        return Math.max(low, Math.min(value, high));
    }

    /** Cuts an over-long cell rather than wrapping it, marking that something was cut. */
    private static String fit(String text, int width) {
        if (text.length() <= width) {
            return text;
        }
        return width <= 1 ? "…" : text.substring(0, width - 1) + "…";
    }

    /**
     * The line under the table, and the only place the view says what it is not. It states that one row
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
}
