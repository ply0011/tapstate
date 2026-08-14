package io.tapstate.cli;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * The in-place view's rule for what to write on each poll. It is a value with memory rather than a
 * loop, so the three redraw rules can be exercised a poll at a time without a terminal, a server or a
 * second of real waiting.
 *
 * <p>The rules pull against each other and both directions matter. A screen that redraws every second
 * with the same content tells the reader nothing and keeps the byte stream from ever idling. A screen
 * that goes completely silent while nothing changes is worse: the reader cannot tell a still row from
 * a view that died, and a view that died looks exactly like good news. So a quiet row still gets a
 * status line, just on a slower cadence — and anything that is a change of state (the view stopped
 * looking, or started again) is written at once rather than held back for it.
 */
final class WatchView {

    /** How often a quiet view refreshes its status line. Slow enough to leave the stream idle between. */
    private static final Duration STATUS_CADENCE = Duration.ofSeconds(5);

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String namespace;

    private Map<String, Object> shown;
    private boolean drawn;
    private String skipping;
    private String lastChangeAt;
    private String lastChangeSummary;
    private Instant lastStatusAt;

    WatchView(String namespace) {
        this.namespace = namespace;
    }

    /**
     * What to write for one poll, in order; empty means write nothing at all. The caller owns the
     * screen — whether the lines replace the frame in place or scroll — and this owns what they say.
     */
    List<String> onPoll(WatchPoll poll, Instant at) {
        return switch (poll) {
            case WatchPoll.Row found -> onRow(found, at);
            case WatchPoll.NoRow ignored -> onNoRow(at);
            case WatchPoll.Skipped skipped -> onSkipped(skipped.reason(), at);
        };
    }

    private List<String> onRow(WatchPoll.Row found, Instant at) {
        DocumentDiff diff = DocumentDiff.between(shown, found.row());
        boolean wasSkipping = skipping != null;
        skipping = null;
        shown = found.row();
        if (!diff.isEmpty()) {
            lastChangeAt = clock(at);
            lastChangeSummary = diff.summary();
            lastStatusAt = at;
            drawn = true;
            return withStatus(WatchRenderer.frame(namespace, found.row(), diff, found.approximateTotal()), at);
        }
        // Unchanged, but the screen may not be: a status line saying the view had stopped looking is
        // still the last thing on it, and nothing else would ever take that back.
        if (wasSkipping || !drawn) {
            lastStatusAt = at;
            drawn = true;
            return withStatus(WatchRenderer.frame(namespace, found.row(),
                    DocumentDiff.between(null, found.row()), found.approximateTotal()), at);
        }
        return dueStatus(at);
    }

    private List<String> onNoRow(Instant at) {
        boolean news = shown != null || skipping != null || !drawn;
        shown = null;
        skipping = null;
        drawn = true;
        if (!news) {
            return dueStatus(at);
        }
        lastStatusAt = at;
        return List.of("no row matches — " + namespace + " has nothing the filter names",
                WatchRenderer.status(lastChangeAt, lastChangeSummary, clock(at)));
    }

    private List<String> onSkipped(String reason, Instant at) {
        // A new reason is a change of state and is written at once. The same reason repeating is the
        // same news, and falls back to the quiet cadence.
        if (reason.equals(skipping)) {
            return dueStatus(at, reason);
        }
        skipping = reason;
        lastStatusAt = at;
        return List.of(WatchRenderer.status(lastChangeAt, lastChangeSummary, clock(at) + " (" + reason + ")"));
    }

    private List<String> withStatus(List<String> frame, Instant at) {
        return java.util.stream.Stream.concat(frame.stream(),
                java.util.stream.Stream.of(WatchRenderer.status(lastChangeAt, lastChangeSummary, clock(at))))
                .toList();
    }

    private List<String> dueStatus(Instant at) {
        return dueStatus(at, null);
    }

    /** The status line, but only once the quiet cadence has come round again. */
    private List<String> dueStatus(Instant at, String reason) {
        if (lastStatusAt != null && Duration.between(lastStatusAt, at).compareTo(STATUS_CADENCE) < 0) {
            return List.of();
        }
        lastStatusAt = at;
        String checked = reason == null ? clock(at) : clock(at) + " (" + reason + ")";
        return List.of(WatchRenderer.status(lastChangeAt, lastChangeSummary, checked));
    }

    private static String clock(Instant at) {
        return CLOCK.format(at.atZone(ZoneId.systemDefault()));
    }
}
