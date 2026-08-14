package io.tapstate.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

/**
 * Renders the appended view: one event per change, oldest first, never redrawn. Like the in-place
 * view it renders from a {@link DocumentDiff} rather than comparing rows itself — the two are two
 * renderings of one comparison, and a second comparison is how they would part ways.
 *
 * <p>Every event opens at column zero with its own time and key, so the stream stays something a
 * reader can pipe into a search. What follows is indented under it, which is why the two are told
 * apart by position rather than by punctuation a grep would have to know about.
 *
 * <p>A list that gained entries shows the entries it gained, not the list again. For the rows worth
 * watching — the ones with an array that grows — reprinting the array is the difference between one
 * line per change and a screen per change.
 */
final class TailRenderer {

    /** The indent under an event line, wide enough to clear the time column. */
    private static final String DETAIL_INDENT = "          ";

    private TailRenderer() {
    }

    /**
     * The lines one change produces: an event line naming when, which row and what changed, then one
     * detail line per field carrying what the event line has no room for. Empty when nothing changed,
     * so a stream never emits an event that says nothing.
     */
    static List<String> lines(String time, String key, DocumentDiff diff) {
        if (diff.isEmpty()) {
            return List.of();
        }
        List<String> lines = new ArrayList<>();
        lines.add(time + "  " + key + "  " + diff.summary());
        for (DocumentChange change : diff.changes()) {
            lines.add(DETAIL_INDENT + change.mark().slot() + " " + change.field() + "  " + detail(change));
        }
        return lines;
    }

    /** What one change says beyond its mark and field: the entries gained, the two values, or nothing. */
    private static String detail(DocumentChange change) {
        if (!change.addedEntries().isEmpty()) {
            StringJoiner entries = new StringJoiner(", ");
            change.addedEntries().forEach(entry -> entries.add(JsonOut.compact(entry)));
            return entries.toString();
        }
        return switch (change.mark()) {
            case CHANGED -> JsonOut.compact(change.before()) + " → " + JsonOut.compact(change.after());
            case ADDED -> JsonOut.compact(change.after());
            case REMOVED -> "(was " + JsonOut.compact(change.before()) + ")";
        };
    }
}
