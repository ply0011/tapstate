package io.tapstate.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The two live views' rendering rules, which are the whole of what a reader sees and therefore the
 * whole of what can mislead them. Two things are pinned here that no other test can pin: the footer's
 * exact words, because they are the one place the view admits what it cannot promise; and that both
 * views report the same changes, because they are two renderings of one comparison and a second
 * comparison is how they would part ways.
 */
class LiveViewRenderersTest {

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    /** The (mark, field) pairs a rendering claims changed, read back out of the text it produced. */
    private static List<String> markedFields(List<String> lines) {
        Pattern marked = Pattern.compile("([+~-]) (\\w+)");
        List<String> found = new ArrayList<>();
        for (String line : lines) {
            Matcher matcher = marked.matcher(line);
            while (matcher.find()) {
                found.add(matcher.group(1) + matcher.group(2));
            }
        }
        return found;
    }

    @Nested
    @DisplayName("the in-place view")
    class WatchFrames {

        @Test
        @DisplayName("says it polls, and says so in the header rather than looking like a push")
        void headerSaysItPolls() {
            List<String> frame = WatchRenderer.frame("views.order_state", row("id", "ord_123"),
                    DocumentDiff.between(null, row("id", "ord_123")), 5L);

            assertThat(frame.get(0))
                    .as("a view that refreshes by asking again says so; a reader who thinks it is pushed "
                            + "reads a one-second-old row as live")
                    .contains("polling every 1s")
                    .contains("views.order_state");
        }

        @Test
        @DisplayName("the footer says what the view cannot promise, word for word")
        void footerAdmitsWhatItCannotPromise() {
            List<String> frame = WatchRenderer.frame("views.order_state", row("id", "ord_123"),
                    DocumentDiff.between(null, row("id", "ord_123")), 5L);

            assertThat(frame.get(frame.size() - 1))
                    .as("the row on screen is the database's first, which is not a stable pick and not "
                            + "the newest — a footer that said either would be a lie the reader cannot check")
                    .startsWith("showing 1 of ~5 (first in natural order — not stable, not the newest)")
                    .endsWith("· use `tail` for the whole table");
        }

        @Test
        @DisplayName("a filtered read gets no count, and renders as no count rather than as zero")
        void filteredReadShowsNoCount() {
            List<String> frame = WatchRenderer.frame("views.order_state", row("id", "ord_123"),
                    DocumentDiff.between(null, row("id", "ord_123")), null);

            String footer = frame.get(frame.size() - 1);
            assertThat(footer)
                    .as("counting a filtered collection is a full scan, so the count is not offered — "
                            + "and an absent count rendered as 0 would be a number that is simply wrong")
                    .startsWith("showing 1 (first in natural order")
                    .doesNotContain("~0")
                    .doesNotContain("of ~");
        }

        @Test
        @DisplayName("the mark sits in the slot beside the field it belongs to, not beside its neighbour")
        void marksLandBesideTheirOwnField() {
            Map<String, Object> before = row("id", "ord_123", "status", "Paid");
            Map<String, Object> after = row("id", "ord_123", "status", "Shipped");
            List<String> frame = WatchRenderer.frame(
                    "views.order_state", after, DocumentDiff.between(before, after), 5L);

            assertThat(frame)
                    .as("the unchanged field keeps an empty slot, so the marked one is the one that moved")
                    .anySatisfy(line -> assertThat(line).startsWith("  id"))
                    .anySatisfy(line -> assertThat(line).startsWith("~ status"));
        }

        @Test
        @DisplayName("a frame with nothing new writes nothing at all in the data area")
        void anUnchangedFrameWritesNothing() {
            Map<String, Object> unchanged = row("id", "ord_123", "status", "Paid");

            assertThat(WatchRenderer.frame("views.order_state", unchanged,
                    DocumentDiff.between(unchanged, unchanged), 5L))
                    .as("redrawing an identical frame every second makes the byte stream never idle and "
                            + "tells the reader nothing; the status line is what says the view is alive")
                    .isEmpty();
        }

        @Test
        @DisplayName("the status line reports both when it last changed and when it last looked")
        void statusLineSeparatesLastChangeFromLastCheck() {
            String status = WatchRenderer.status("14:22:15", "~status", "14:22:19");

            assertThat(status)
                    .as("without the second half a reader cannot tell a quiet row from a dead view — the "
                            + "same silent failure as a truncated read that looks like a small collection")
                    .contains("last change 14:22:15")
                    .contains("~status")
                    .contains("checked 14:22:19");
        }

        @Test
        @DisplayName("a row that has never changed says so, rather than reporting a change at time null")
        void statusLineSaysWhenNothingHasChangedYet() {
            assertThat(WatchRenderer.status(null, null, "14:22:19"))
                    .as("a view opened on a quiet row has no last change to name, and naming one anyway "
                            + "is the view's first statement to the reader being false")
                    .doesNotContain("null")
                    .contains("no change yet")
                    .contains("checked 14:22:19");
        }
    }

    @Nested
    @DisplayName("the appended view")
    class TailLines {

        @Test
        @DisplayName("every event starts with its own time and key, so the stream stays pipeable")
        void eventLineCarriesTimeAndKey() {
            Map<String, Object> before = row("id", "ord_123", "status", "Paid");
            Map<String, Object> after = row("id", "ord_123", "status", "Shipped");

            List<String> lines = TailRenderer.lines("14:22:15", "ord_123", DocumentDiff.between(before, after));

            assertThat(lines.get(0)).startsWith("14:22:15  ord_123  ");
            assertThat(lines.get(0)).contains("~status");
        }

        @Test
        @DisplayName("a list that grew shows the entries that arrived, not the list all over again")
        void showsTheEntriesAListGained() {
            Map<String, Object> shipment = row("shipment_id", "shp_2", "carrier", "FedEx");
            Map<String, Object> before = row("id", "ord_123", "shipments", List.of(row("shipment_id", "shp_1")));
            Map<String, Object> after = row("id", "ord_123",
                    "shipments", List.of(row("shipment_id", "shp_1"), shipment));

            List<String> lines = TailRenderer.lines("14:22:11", "ord_123", DocumentDiff.between(before, after));

            assertThat(lines.get(0)).contains("+1 shipments");
            assertThat(lines)
                    .as("the entry that arrived is the news; reprinting the array turns one line into a "
                            + "screen for exactly the rows worth watching")
                    .anySatisfy(line -> assertThat(line).contains("shp_2").contains("FedEx"))
                    .noneSatisfy(line -> assertThat(line).contains("shp_1"));
        }

        @Test
        @DisplayName("nothing changed means no line at all, not an empty one")
        void writesNothingForAnUnchangedRow() {
            Map<String, Object> unchanged = row("id", "ord_123");

            assertThat(TailRenderer.lines("14:22:15", "ord_123", DocumentDiff.between(unchanged, unchanged)))
                    .isEmpty();
        }
    }

    @Test
    @DisplayName("both views, given one comparison, report the same changes in the same order")
    void bothViewsReportTheSameChanges() {
        Map<String, Object> before = row(
                "id", "ord_123", "status", "Paid", "coupon", "SUMMER",
                "shipments", List.of(row("shipment_id", "shp_1")));
        Map<String, Object> after = row(
                "id", "ord_123", "status", "Shipped",
                "shipments", List.of(row("shipment_id", "shp_1"), row("shipment_id", "shp_2")),
                "tracking", "1Z999");
        DocumentDiff diff = DocumentDiff.between(before, after);

        List<String> inPlace = markedFields(WatchRenderer.frame("views.order_state", after, diff, 5L));
        List<String> appended = markedFields(TailRenderer.lines("14:22:15", "ord_123", diff));

        assertThat(diff.changes())
                .as("an empty comparison would make the comparison below vacuous")
                .isNotEmpty();
        assertThat(inPlace)
                .as("two renderings of one comparison; a view that walked the rows itself would drift "
                        + "from the other one field at a time, and both would look right alone")
                .containsExactlyElementsOf(appended);
    }
}
