package io.tapstate.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The in-place view's rendering rules, which are the whole of what a reader sees and therefore the
 * whole of what can mislead them. What is pinned here that no other test pins is the footer's exact
 * words, because they are the one place the view admits what it cannot promise.
 *
 * <p>It once also pinned that both live views reported the same changes, on the reasoning that they
 * were two renderings of one comparison and that a second comparison is how they would part ways.
 * That is no longer a thing that can be true: the appended view compares nothing at all now — it
 * prints the rows a change carried and works nothing out — so there is no one comparison for the two
 * to render, and nothing for them to part ways over. Its own rules live beside it.
 */
class LiveViewRenderersTest {

    /** A width with room for both value columns, so the narrow fallback is not what is under test. */
    private static final int WIDE = 100;

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    @Nested
    @DisplayName("the in-place view")
    class WatchFrames {

        @Test
        @DisplayName("says it polls, and says so in the header rather than looking like a push")
        void headerSaysItPolls() {
            List<String> frame = WatchRenderer.frame("views.order_state", row("id", "ord_123"), null,
                    DocumentDiff.between(null, row("id", "ord_123")), 5L, WIDE);

            assertThat(frame.get(0))
                    .as("a view that refreshes by asking again says so; a reader who thinks it is pushed "
                            + "reads a one-second-old row as live")
                    .contains("polling every 1s")
                    .contains("views.order_state");
        }

        @Test
        @DisplayName("the footer says what the view cannot promise, word for word")
        void footerAdmitsWhatItCannotPromise() {
            List<String> frame = WatchRenderer.frame("views.order_state", row("id", "ord_123"), null,
                    DocumentDiff.between(null, row("id", "ord_123")), 5L, WIDE);

            assertThat(WatchRenderer.footer(5L))
                    .as("the row on screen is the database's first, which is not a stable pick and not "
                            + "the newest — a footer that said either would be a lie the reader cannot check")
                    .startsWith("showing 1 of ~5 (first in natural order — not stable, not the newest)")
                    .endsWith("· use `tail` for the whole table");
            assertThat(frame)
                    .as("and it is under the table, where the reader meets it")
                    .anySatisfy(line -> assertThat(line).startsWith("showing 1 of ~5"));
        }

        @Test
        @DisplayName("a filtered read gets no count, and renders as no count rather than as zero")
        void filteredReadShowsNoCount() {
            List<String> frame = WatchRenderer.frame("views.order_state", row("id", "ord_123"), null,
                    DocumentDiff.between(null, row("id", "ord_123")), null, WIDE);

            String footer = WatchRenderer.footer(null);
            assertThat(frame).anySatisfy(line -> assertThat(line).startsWith("showing 1 (first"));
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
                    "views.order_state", after, before, DocumentDiff.between(before, after), 5L, WIDE);

            assertThat(frame)
                    .as("the unchanged field keeps an empty slot, so the marked one is the one that moved")
                    .anySatisfy(line -> assertThat(line).contains("\u2502   id"))
                    .anySatisfy(line -> assertThat(line).contains("\u2502 ~ status"));
        }

        @Test
        @DisplayName("a frame is always the whole table, so a caller redrawing in place cannot erase more")
        void everyFrameIsTheWholeTable() {
            Map<String, Object> unchanged = row("id", "ord_123", "status", "Paid");

            List<String> frame = WatchRenderer.frame("views.order_state", unchanged, unchanged,
                    DocumentDiff.between(unchanged, unchanged), 5L, WIDE);

            assertThat(frame)
                    .as("the caller erases as many lines as it last wrote; a frame that left the "
                            + "unchanged fields out would take them off the screen and leave the rest")
                    .anySatisfy(line -> assertThat(line).contains("id"))
                    .anySatisfy(line -> assertThat(line).contains("status"));
            assertThat(frame.get(0)).startsWith("\u250c");
            // Whether an unchanged screen is rewritten at all is the view's decision, not this one's:
            // it is the only place that knows when the screen was last written to.
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
    @DisplayName("an embedded value in the in-place view")
    class NestedValues {

        private static Map<String, Object> item(String sku, int qty) {
            return row("sku", sku, "name", "Gadget", "qty", qty, "price", 23.49,
                    "warehouse", row("code", "WH-1", "shelf", "S3", "bin", "B11"));
        }

        /** What a frame line holds in its first value column, with the padding taken off. */
        private static String cellOf(String line) {
            String[] cells = line.split("\u2502");
            return cells.length > 2 ? cells[2].trim() : "";
        }

        private static List<String> frameOf(Map<String, Object> now, Map<String, Object> was) {
            return WatchRenderer.frame("views.order_state", now, was,
                    DocumentDiff.between(was, now), 5L, WIDE);
        }

        @Test
        @DisplayName("one that fits stays on its own line, so the common case does not grow taller")
        void aValueThatFitsIsNotExpanded() {
            List<String> frame = frameOf(row("tags", List.of("normal")), null);

            assertThat(frame).as("expanding what already fits spends screen height for nothing")
                    .anySatisfy(line -> assertThat(line).contains("[\"normal\"]"));
            assertThat(String.join("\n", frame)).doesNotContain("(1 items)");
        }

        @Test
        @DisplayName("a long array keeps both ends and says how many it left out in between")
        void aLongArrayKeepsBothEndsAndCountsTheMiddle() {
            List<Object> many = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                many.add(item("SKU-" + i, i + 1));
            }

            String text = String.join("\n", frameOf(row("items", many), null));

            assertThat(text).as("the first elements are what the reader orients on").contains("SKU-0");
            assertThat(text).as("the last ones are where an append lands").contains("SKU-7");
            assertThat(text).as("what was left out has to be counted, or the array reads as complete")
                    .contains("3 more");
            assertThat(text).as("the total is the one number a truncated array cannot imply")
                    .contains("(8 items)");
            assertThat(text).as("the middle is what gets dropped").doesNotContain("SKU-4");
        }

        @Test
        @DisplayName("a level is opened up only when it does not fit — one that fits stays on its line")
        void onlyWhatDoesNotFitIsOpenedUp() {
            String text = String.join("\n", frameOf(row("shipping",
                    row("method", "express", "carrier", "SF", "insured", true, "priority", 1,
                            "meta", row("v", 1))), null));

            assertThat(text).as("the field's own keys are what the reader came for")
                    .contains("\"method\": \"express\"").contains("\"carrier\": \"SF\"");
            assertThat(text).as("the inner value fits its own line, so opening it up too would spend "
                            + "height on something already readable")
                    .contains("\"meta\": {\"v\": 1}");
        }

        @Test
        @DisplayName("the element that changed is readable, not cut out of the middle")
        void theChangedElementIsReadableRatherThanCutAway() {
            Map<String, Object> was = row("items", List.of(item("B-7", 2)));
            Map<String, Object> now = row("items", List.of(item("B-7", 9)));

            String text = String.join("\n", frameOf(now, was));

            // The mark says something moved. Before this rule the two cells were cut at the same
            // character offset, so both ends matched and the one number that differed was inside the
            // part that had been removed -- a diff that announces itself and then hides what it is.
            assertThat(text).as("the new value has to be on screen for the mark to mean anything")
                    .contains("\"qty\": 9");
            assertThat(text).as("and the old one, or there is nothing to compare it against")
                    .contains("\"qty\": 2");
        }

        @Test
        @DisplayName("opening up stops two levels down, so a deep value cannot take the whole screen")
        void openingUpStopsTwoLevelsDown() {
            List<Object> many = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                many.add(item("SKU-" + i, i + 1));
            }

            List<String> frame = frameOf(row("items", many), null);

            assertThat(String.join("\n", frame))
                    .as("an element opens, which is what makes the changed one readable")
                    .contains("\"sku\": \"SKU-0\"");
            // Asserted as "no line of its own", not as "the text still holds it inline": the inline
            // form is cut here, so a containment check would be asserting the cut rather than the depth.
            assertThat(frame).as("and there it stops -- five elements each opened to their own depth is "
                            + "how one field comes to own a frame the view still has to redraw over")
                    .noneSatisfy(line -> assertThat(cellOf(line)).startsWith("\"code\""));
        }

        @Test
        @DisplayName("the field name is written once, beside the first line of its own value")
        void theFieldNameIsWrittenOncePerValue() {
            List<Object> many = new java.util.ArrayList<>();
            for (int i = 0; i < 8; i++) {
                many.add(item("SKU-" + i, i + 1));
            }

            // The field column only -- the closing line of an array says "(8 items)" in the value
            // column, which is the same word and not a second naming of the field.
            long named = frameOf(row("items", many), null).stream()
                    .filter(line -> line.startsWith("\u2502 "))
                    .filter(line -> line.indexOf('\u2502', 1) > 0)
                    .filter(line -> line.substring(0, line.indexOf('\u2502', 1)).contains("items"))
                    .count();

            assertThat(named).as("repeating it down the cell reads as several fields of the same name")
                    .isEqualTo(1);
        }
    }
}
