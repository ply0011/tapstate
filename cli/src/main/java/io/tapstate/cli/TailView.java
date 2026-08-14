package io.tapstate.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the appended view writes per change, and the memory it needs to write it.
 *
 * <p>A change stream carries the row as it is, never as it was. So the only previous version this
 * view can show is <em>the one it showed the reader a moment ago</em> — a statement about this
 * stream, which the reader watched, rather than a claim about what the store held, which the stream
 * never tells it. Everything else here follows from that:
 *
 * <ul>
 *   <li>A key seen for the first time has no previous version, and is shown whole — as a row
 *       arriving, not as a row edited from nothing.
 *   <li>A field an update did not mention is not a field that went away. A change stream may carry
 *       only the fields a write touched, so reporting absences as removals would announce most of the
 *       row as gone on every update. A field really being removed is therefore not reported, and that
 *       is the trade: the loud, constant, wrong answer is worse than the quiet, rare, incomplete one.
 *   <li>Its memory is bounded. A follow on a busy table meets unboundedly many keys, and a view that
 *       remembered them all would be a leak that grows with someone else's write rate. A key dropped
 *       is a key seen for the first time when it next changes, which is the honest rendering anyway.
 * </ul>
 *
 * <p>It reports events, not state, which is the opposite of the in-place view: a write that left the
 * row looking the same still gets a line, because the store did something and a stream that silently
 * dropped it would be under-reporting what happened.
 */
final class TailView {

    /** How many rows' last-shown versions are kept. Beyond this the coldest is dropped. */
    private final int remembered;

    /** Last-shown version per key, in access order so the coldest is the one at the front. */
    private final LinkedHashMap<String, Map<String, Object>> shown;

    TailView(int remembered) {
        this.remembered = remembered;
        this.shown = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Map<String, Object>> eldest) {
                return size() > TailView.this.remembered;
            }
        };
    }

    /** The lines one change produces, oldest first. Never empty: the stream reported something. */
    List<String> onChange(TailChange change) {
        if (change.deleted()) {
            shown.remove(change.key());
            return List.of(change.at() + "  " + change.key() + "  deleted");
        }
        Map<String, Object> previous = shown.get(change.key());
        DocumentDiff diff = DocumentDiff.between(previous, merged(previous, change.row()));
        shown.put(change.key(), merged(previous, change.row()));
        List<String> lines = TailRenderer.lines(change.at(), change.key(), diff);
        if (!lines.isEmpty()) {
            return lines;
        }
        // The store wrote something that left the row looking the same. Reporting events rather than
        // state, this view says so instead of dropping it.
        return List.of(change.at() + "  " + change.key() + "  written, unchanged");
    }

    /**
     * The row as it now stands on screen: what was there, with what the change carried laid over it.
     * Laying over rather than replacing is what keeps an update that mentioned two fields from
     * reading as a row that lost the rest.
     */
    private static Map<String, Object> merged(Map<String, Object> previous, Map<String, Object> carried) {
        if (previous == null) {
            return carried;
        }
        Map<String, Object> now = new LinkedHashMap<>(previous);
        now.putAll(carried);
        return now;
    }

    /** The keys whose last-shown version is currently remembered, coldest first. */
    List<String> remembering() {
        return new ArrayList<>(shown.keySet());
    }
}
