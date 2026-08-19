package io.tapstate.cli;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.stream.Stream;

/**
 * The in-place view's rule for what to write on each poll. It is a value with memory rather than a
 * loop, so the redraw rules can be exercised a poll at a time without a terminal, a server or a
 * second of real waiting.
 *
 * <p>It holds two versions of the row: the one showing now, and the one that was showing before the
 * most recent change. A change pushes the current version one place to the right and takes its place,
 * so the previous-version column is always exactly one step behind — not the version the view opened on,
 * which would drift further from useful the longer the view stayed up.
 *
 * <p><b>Anything it writes is the whole screen.</b> The caller redraws in place by erasing as many
 * lines as it last wrote, so a write that carried only the part that changed would erase the rest of
 * the row and leave that one part behind. Writing nothing at all is the way to leave the screen
 * alone; there is no partial write.
 *
 * <p>The rules pull against each other and both directions matter. A screen that redraws every second
 * with the same content tells the reader nothing and keeps the byte stream from ever idling. A screen
 * that goes completely silent while nothing changes is worse: the reader cannot tell a still row from
 * a view that died, and a view that died looks exactly like good news. So a quiet row still refreshes,
 * just on a slower cadence — and anything that is a change of state (the view stopped looking, or
 * started again) is written at once rather than held back for it.
 */
final class WatchView {

    /** How often a quiet view refreshes. Slow enough to leave the stream idle between. */
    private static final Duration STATUS_CADENCE = Duration.ofSeconds(5);

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final String namespace;

    /** Asked again on every frame, so a window resized mid-run is picked up by the next redraw. */
    private final IntSupplier width;

    private Map<String, Object> shown;
    private Map<String, Object> previous;
    private Long total;
    private boolean drawn;
    private String skipping;
    private String lastChangeAt;
    private String lastChangeSummary;
    private Instant lastStatusAt;

    WatchView(String namespace, IntSupplier width) {
        this.namespace = namespace;
        this.width = width;
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
        DocumentDiff moved = DocumentDiff.between(shown, found.row());
        boolean wasSkipping = skipping != null;
        skipping = null;
        total = found.approximateTotal();
        if (!moved.isEmpty()) {
            // The version being replaced is what goes on the right. On the very first poll there is
            // nothing to replace, so the right column stays empty rather than repeating the left.
            if (shown != null) {
                previous = shown;
            }
            shown = found.row();
            lastChangeAt = clock(at);
            lastChangeSummary = moved.summary();
            return redraw(at, null);
        }
        shown = found.row();
        // Unchanged, but the screen may not be: a view that had stopped looking said so, and nothing
        // else would ever take that back.
        if (wasSkipping || !drawn || statusDue(at)) {
            return redraw(at, null);
        }
        return List.of();
    }

    private List<String> onNoRow(Instant at) {
        boolean news = shown != null || skipping != null || !drawn;
        shown = null;
        previous = null;
        skipping = null;
        if (!news && !statusDue(at)) {
            return List.of();
        }
        drawn = true;
        lastStatusAt = at;
        return Stream.concat(
                WatchRenderer.wrap("no row matches — " + namespace + " has nothing the filter names",
                        width.getAsInt()).stream(),
                WatchRenderer.wrap(WatchRenderer.status(lastChangeAt, lastChangeSummary, clock(at)),
                        width.getAsInt()).stream())
                .toList();
    }

    private List<String> onSkipped(String reason, Instant at) {
        // A new reason is a change of state and is written at once. The same reason repeating is the
        // same news, and falls back to the quiet cadence.
        boolean sameReason = reason.equals(skipping);
        skipping = reason;
        if (sameReason && !statusDue(at)) {
            return List.of();
        }
        return redraw(at, reason);
    }

    /**
     * The whole screen as it should now read. Before any row has been seen there is no table to draw
     * and the status line is the whole of it, which is why this is not simply the frame.
     */
    private List<String> redraw(Instant at, String reason) {
        lastStatusAt = at;
        drawn = true;
        String checked = reason == null ? clock(at) : clock(at) + " (" + reason + ")";
        int screen = width.getAsInt();
        // Wrapped for the same reason the footer is: the caller counts the lines it wrote, and a line
        // the terminal wraps for it takes a row that was never counted.
        List<String> status = WatchRenderer.wrap(
                WatchRenderer.status(lastChangeAt, lastChangeSummary, checked), screen);
        if (shown == null) {
            return status;
        }
        List<String> frame = WatchRenderer.frame(namespace, shown, previous,
                DocumentDiff.between(previous, shown), total, screen);
        return Stream.concat(frame.stream(), status.stream()).toList();
    }

    /** Whether the quiet cadence has come round again. */
    private boolean statusDue(Instant at) {
        return lastStatusAt == null || Duration.between(lastStatusAt, at).compareTo(STATUS_CADENCE) >= 0;
    }

    private static String clock(Instant at) {
        return CLOCK.format(at.atZone(ZoneId.systemDefault()));
    }
}
