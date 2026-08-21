package io.tapstate.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the in-place view writes on each poll, which is the whole of the rule "do not redraw a screen
 * that says the same thing" — and its counterweight, "never go completely silent". A view that froze
 * while it was quiet would leave a reader unable to tell a still row from a dead view, which is the
 * same silent failure as a truncated read passing for a small collection.
 */
class WatchViewTest {

    private static final Instant START = Instant.parse("2026-08-14T14:22:11Z");

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    private static WatchView view() {
        return new WatchView("views.order_state", () -> 100);
    }

    @Test
    @DisplayName("the first poll draws the row, since every field of it is news")
    void firstPollDrawsTheRow() {
        List<String> written = view().onPoll(new WatchPoll.Row(row("id", "ord_123"), 5L), START);

        assertThat(written).isNotEmpty();
        assertThat(written.get(0)).contains("polling every 1s");
    }

    @Test
    @DisplayName("a poll that found the row unchanged writes nothing in the data area")
    void unchangedPollWritesNothing() {
        WatchView view = view();
        Map<String, Object> same = row("id", "ord_123", "status", "Paid");
        view.onPoll(new WatchPoll.Row(same, 5L), START);

        List<String> written = view.onPoll(new WatchPoll.Row(same, 5L), START.plusSeconds(1));

        assertThat(written)
                .as("a second-by-second redraw of an identical screen says nothing and keeps the byte "
                        + "stream from ever idling")
                .isEmpty();
    }

    @Test
    @DisplayName("a quiet row still gets a status line, on its own slower cadence")
    void quietRowStillReportsThatTheViewIsAlive() {
        WatchView view = view();
        Map<String, Object> same = row("id", "ord_123");
        view.onPoll(new WatchPoll.Row(same, 5L), START);

        assertThat(view.onPoll(new WatchPoll.Row(same, 5L), START.plusSeconds(2)))
                .as("every quiet second is not worth a line")
                .isEmpty();
        assertThat(view.onPoll(new WatchPoll.Row(same, 5L), START.plus(Duration.ofSeconds(6))))
                .as("but going silent forever is how a reader stops being able to tell a still row from "
                        + "a view that died")
                .anySatisfy(line -> assertThat(line).contains("checked"))
                .as("and the refresh carries the whole screen: the caller erases what it last drew, so a "
                        + "status line on its own would take the row down with it")
                .anySatisfy(line -> assertThat(line).contains("ord_123"));
    }

    @Test
    @DisplayName("a changed row is redrawn whole, and the footer names what changed")
    void changedRowIsRedrawnWithWhatChanged() {
        WatchView view = view();
        view.onPoll(new WatchPoll.Row(row("id", "ord_123", "status", "Paid"), 5L), START);

        List<String> written = view.onPoll(
                new WatchPoll.Row(row("id", "ord_123", "status", "Shipped"), 5L), START.plusSeconds(4));

        assertThat(written).anySatisfy(line -> assertThat(line).contains("\u2502 ~ status"));
        assertThat(written).last(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("~status");
    }

    @Test
    @DisplayName("a skipped frame says so at once, rather than up to five seconds later")
    void aNewlySkippedFrameIsReportedImmediately() {
        WatchView view = view();
        view.onPoll(new WatchPoll.Row(row("id", "ord_123"), 5L), START);

        List<String> written = view.onPoll(new WatchPoll.Skipped("busy"), START.plusSeconds(1));

        assertThat(written)
                .as("the reason the view is not looking is a change of state, and throttling it would "
                        + "hold back the line that explains a screen that stopped moving")
                .anySatisfy(line -> assertThat(line).contains("busy"));
    }

    @Test
    @DisplayName("a frame it could not take redraws the last good one, rather than letting it be erased")
    void aSkippedFrameKeepsTheLastGoodData() {
        WatchView view = view();
        view.onPoll(new WatchPoll.Row(row("id", "ord_123", "status", "Paid"), 5L), START);

        List<String> written = view.onPoll(new WatchPoll.Skipped("unreachable"), START.plusSeconds(1));

        assertThat(written)
                .as("clearing the screen to report a blip throws away the only data the reader has, and "
                        + "writing the reason alone does exactly that: the caller erases what it drew")
                .anySatisfy(line -> assertThat(line).contains("ord_123"))
                .anySatisfy(line -> assertThat(line).contains("unreachable"));
    }

    @Test
    @DisplayName("the same skip repeating does not repeat its line every second")
    void arepeatedSkipIsNotRepeatedEverySecond() {
        WatchView view = view();
        view.onPoll(new WatchPoll.Row(row("id", "ord_123"), 5L), START);
        view.onPoll(new WatchPoll.Skipped("busy"), START.plusSeconds(1));

        assertThat(view.onPoll(new WatchPoll.Skipped("busy"), START.plusSeconds(2)))
                .as("one skip is news; the same skip a second later is the same news")
                .isEmpty();
    }

    @Test
    @DisplayName("coming back after a skip redraws, because the reader was last told it was not looking")
    void recoveringFromASkipRedraws() {
        WatchView view = view();
        Map<String, Object> same = row("id", "ord_123", "status", "Paid");
        view.onPoll(new WatchPoll.Row(same, 5L), START);
        view.onPoll(new WatchPoll.Skipped("unreachable"), START.plusSeconds(1));

        assertThat(view.onPoll(new WatchPoll.Row(same, 5L), START.plusSeconds(2)))
                .as("the row is unchanged, but the screen is not: it is still showing a line saying the "
                        + "view had lost the server, and nothing else would ever take it back")
                .isNotEmpty();
    }

    @Test
    @DisplayName("a filter that matches nothing says so, and does not leave the old row on screen")
    void reportsWhenTheRowItWasWatchingIsNoLongerThere() {
        WatchView view = view();
        view.onPoll(new WatchPoll.Row(row("id", "ord_123", "status", "Paid"), 5L), START);

        List<String> written = view.onPoll(new WatchPoll.NoRow(), START.plusSeconds(1));

        assertThat(written)
                .as("leaving the last row up while it no longer matches shows the reader something that "
                        + "is no longer true, with nothing on screen to say so")
                .isNotEmpty()
                .anySatisfy(line -> assertThat(line).contains("no row"));
    }
}
