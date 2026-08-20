package io.tapstate.cli;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The in-place view shows two versions side by side: what the row holds now, and what it held one
 * version ago. A new version enters on the left and pushes the one it replaced to the right, so the
 * right column is always exactly one step behind — not the version the view started on.
 */
class WatchTwoColumnTest {

    private static final Instant START = Instant.parse("2026-08-19T08:22:11Z");
    private static final int WIDE = 100;

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            r.put((String) pairs[i], pairs[i + 1]);
        }
        return r;
    }

    private static WatchView view() {
        return new WatchView("shop.orders", () -> WIDE);
    }

    /** The cell text of one field's row, split on the column rule, trimmed of border and padding. */
    private static List<String> cells(List<String> written, String field) {
        return written.stream()
                .filter(l -> l.contains(field))
                .findFirst()
                .map(l -> java.util.Arrays.stream(l.split("│"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList())
                .orElseThrow(() -> new AssertionError("no line for field " + field + " in " + written));
    }

    @Test
    @DisplayName("on the first frame the was column is empty — there is no version before this one")
    void firstFrameHasNothingOnTheRight() {
        List<String> written = view().onPoll(new WatchPoll.Row(row("order_no", "SO-1001", "amount", 27), 36L), START);

        assertThat(cells(written, "order_no"))
                .as("field and current value only; nothing has been pushed right yet")
                .containsExactly("order_no", "\"SO-1001\"");
        assertThat(cells(written, "amount")).containsExactly("amount", "27");
    }

    @Test
    @DisplayName("after a change every field shows its previous value, the unchanged ones included")
    void everyFieldCarriesItsPreviousValue() {
        WatchView view = view();
        view.onPoll(new WatchPoll.Row(row("order_no", "SO-1001", "status", "pending", "amount", 27), 36L), START);

        List<String> written = view.onPoll(
                new WatchPoll.Row(row("order_no", "SO-1001", "status", "paid", "amount", 696), 36L),
                START.plusSeconds(1));

        assertThat(cells(written, "status"))
                .as("the field that moved shows both versions")
                .containsExactly("~ status", "\"paid\"", "\"pending\"");
        assertThat(cells(written, "order_no"))
                .as("a field that did not move still shows its previous value — no change detection in the render")
                .containsExactly("order_no", "\"SO-1001\"", "\"SO-1001\"");
    }

    @Test
    @DisplayName("the right column is one version back, not the version the view started on")
    void theRightColumnIsOneVersionBackNotTheBaseline() {
        WatchView view = view();
        view.onPoll(new WatchPoll.Row(row("amount", 1), 36L), START);
        view.onPoll(new WatchPoll.Row(row("amount", 2), 36L), START.plusSeconds(1));

        List<String> written = view.onPoll(new WatchPoll.Row(row("amount", 3), 36L), START.plusSeconds(2));

        assertThat(cells(written, "amount"))
                .as("3 pushed 2 to the right; 1 is gone")
                .containsExactly("~ amount", "3", "2");
    }

    @Test
    @DisplayName("a quiet poll past the cadence rewrites the whole frame, never the status line alone")
    void aQuietPollRewritesTheWholeFrame() {
        WatchView view = view();
        List<String> first = view.onPoll(new WatchPoll.Row(row("order_no", "SO-1001"), 36L), START);

        assertThat(view.onPoll(new WatchPoll.Row(row("order_no", "SO-1001"), 36L), START.plusSeconds(2)))
                .as("inside the cadence it still writes nothing at all")
                .isEmpty();

        List<String> quiet = view.onPoll(new WatchPoll.Row(row("order_no", "SO-1001"), 36L),
                START.plus(Duration.ofSeconds(6)));

        assertThat(quiet)
                .as("the caller erases as many lines as it last drew, so a partial write erases the row")
                .hasSameSizeAs(first)
                .anySatisfy(l -> assertThat(l).contains("SO-1001"));
    }

    @Test
    @DisplayName("a skipped frame redraws the last row it had, rather than leaving the caller to erase it")
    void aSkippedFrameStillRedrawsTheRow() {
        WatchView view = view();
        List<String> first = view.onPoll(new WatchPoll.Row(row("order_no", "SO-1001"), 36L), START);

        List<String> skipped = view.onPoll(new WatchPoll.Skipped("busy"), START.plusSeconds(1));

        assertThat(skipped)
                .hasSameSizeAs(first)
                .anySatisfy(l -> assertThat(l).contains("SO-1001"))
                .anySatisfy(l -> assertThat(l).contains("busy"));
    }

    @Test
    @DisplayName("the columns are headed by what they hold: the current version and the one before it")
    void theColumnsSayWhichVersionTheyHold() {
        List<String> written = view().onPoll(new WatchPoll.Row(row("order_no", "SO-1001"), 36L), START);

        assertThat(written.get(1))
                .as("the two columns are two versions of one row, not two rows; heading them anything "
                        + "that reads as a comparison between records misnames what the reader is seeing")
                .contains("current")
                .contains("previous");
    }

    @Test
    @DisplayName("the frame is boxed: an outer border and a rule between the columns")
    void theFrameIsBoxed() {
        List<String> written = view().onPoll(new WatchPoll.Row(row("order_no", "SO-1001"), 36L), START);

        assertThat(written).first(org.assertj.core.api.InstanceOfAssertFactories.STRING).startsWith("┌");
        assertThat(written).anySatisfy(l -> assertThat(l).startsWith("└"));
        assertThat(written)
                .as("the field row carries the column rule")
                .anySatisfy(l -> assertThat(l).contains("SO-1001").contains("│"));
    }

    @Test
    @DisplayName("the table keeps its width when the first change lands, rather than growing under the reader")
    void theTableDoesNotResizeWhenTheFirstChangeArrives() {
        WatchView view = view();
        Map<String, Object> before = row("_id", "6a852b1bc15d03cdbbfe6911", "status", "pending");

        List<String> first = view.onPoll(new WatchPoll.Row(before, 36L), START);
        List<String> changed = view.onPoll(
                new WatchPoll.Row(row("_id", "6a852b1bc15d03cdbbfe6911", "status", "paid"), 36L),
                START.plusSeconds(1));

        assertThat(changed.get(0).length())
                .as("an empty was column still has to be sized for what will land in it; sizing it to "
                        + "its content makes the whole table jump wider the moment anything changes")
                .isEqualTo(first.get(0).length());
        assertThat(cells(first, "_id"))
                .as("and the value it already holds is not truncated to make room for nothing")
                .containsExactly("_id", "\"6a852b1bc15d03cdbbfe6911\"");
    }

    @Test
    @DisplayName("an embedded value too long for its column is opened up rather than cut")
    void anOverlongEmbeddedValueIsOpenedUp() {
        WatchView view = new WatchView("shop.orders", () -> 100);
        Map<String, Object> stamp = row("date", "2026-08-19T04:03:39.000Z", "timestamp", 1787112219);

        List<String> written = view.onPoll(new WatchPoll.Row(row("created", stamp), 36L), START);

        // This used to be cut in the middle, on the reasoning that both ends of a long value are what
        // tell two of them apart. That holds for a value with no parts. It fails for one with parts:
        // two versions of an embedded value share a long head and a long tail, so a cut at a fixed
        // offset removes precisely what they differ in -- the view marks the field changed and then
        // shows two cells that read identically.
        assertThat(String.join("\n", written))
                .as("every member of it is legible, which is the whole reason not to cut it")
                .contains("\"date\": \"2026-08-19T04:03:39.000Z\"")
                .contains("\"timestamp\": 1787112219");
        assertThat(written)
                .as("and it still fits the screen, or the table overflows it")
                .allSatisfy(l -> assertThat(l.length()).isLessThanOrEqualTo(100));
    }

    @Test
    @DisplayName("a long value with no parts still keeps both ends, cutting the middle out")
    void anOverlongScalarIsStillCutInTheMiddle() {
        WatchView view = new WatchView("shop.orders", () -> 100);

        List<String> written = view.onPoll(new WatchPoll.Row(row("note",
                "a very long single scalar value that certainly does not fit its column at all, no"),
                36L), START);
        String cell = cells(written, "note").get(1);

        assertThat(cell)
                .as("the tail of a long value is what tells two of them apart; cutting it off leaves "
                        + "every long value looking like every other one with the same prefix")
                .startsWith("\"a very long")
                .endsWith("at all, no\"")
                .contains("…");
        assertThat(written)
                .as("and it still fits the screen, or the table overflows it")
                .allSatisfy(l -> assertThat(l.length()).isLessThanOrEqualTo(100));
    }

    @Test
    @DisplayName("the identity field is cut rather than opened up, since it is on every single row")
    void theIdentityFieldIsCutRatherThanOpenedUp() {
        WatchView view = new WatchView("shop.orders", () -> 100);
        // The shape a Mongo _id actually arrives in: an object, not a string.
        Map<String, Object> id = row("date", "2026-08-19T04:03:39.000Z", "timestamp", 1787112219);

        List<String> written = view.onPoll(new WatchPoll.Row(row("_id", id), 36L), START);
        String cell = cells(written, "_id").get(1);

        assertThat(cell)
                .as("this field is on every row and its converted form carries only the second the id "
                        + "was made in, so three further lines of it are spent on every frame for nothing")
                .startsWith("{\"date\": \"2026")
                .endsWith("1787112219}")
                .contains("\u2026");
    }

    @Test
    @DisplayName("the exception is the field's name, not its shape — the same value elsewhere opens up")
    void theExceptionIsTheNameRatherThanTheShape() {
        WatchView view = new WatchView("shop.orders", () -> 100);
        Map<String, Object> sameShape = row("date", "2026-08-19T04:03:39.000Z", "timestamp", 1787112219);

        List<String> written = view.onPoll(new WatchPoll.Row(row("created", sameShape), 36L), START);

        // Pinned as a pair with the test above. Written instead as a rule about small embedded values,
        // the exception would silently take the expansion back for every one of them.
        assertThat(String.join("\n", written))
                .as("a value the reader actually reads is still opened up")
                .contains("\"timestamp\": 1787112219");
    }

    @Test
    @DisplayName("a terminal too narrow for two columns drops the was column instead of overflowing")
    void aNarrowTerminalDropsTheWasColumn() {
        WatchView narrow = new WatchView("shop.orders", () -> 30);
        narrow.onPoll(new WatchPoll.Row(row("status", "pending"), 36L), START);

        List<String> written = narrow.onPoll(new WatchPoll.Row(row("status", "paid"), 36L), START.plusSeconds(1));

        assertThat(written)
                .as("no line may exceed the terminal width")
                .allSatisfy(l -> assertThat(l.length()).isLessThanOrEqualTo(30));
        assertThat(cells(written, "status"))
                .as("only the current value survives the squeeze")
                .containsExactly("~ status", "\"paid\"");
    }
}
