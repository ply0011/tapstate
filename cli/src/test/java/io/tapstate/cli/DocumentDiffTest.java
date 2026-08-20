package io.tapstate.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The one place two versions of a row are compared. Both live views render from this, which is why it
 * is a value rather than a step inside either of them: a second comparison written for the second view
 * is how the two would start disagreeing about what changed.
 */
class DocumentDiffTest {

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    @Test
    @DisplayName("a scalar that changed is one changed field, carrying both values")
    void reportsAChangedScalarWithBothValues() {
        DocumentDiff diff = DocumentDiff.between(
                row("id", "ord_123", "status", "Paid"),
                row("id", "ord_123", "status", "Shipped"));

        assertThat(diff.changes()).hasSize(1);
        DocumentChange change = diff.changes().get(0);
        assertThat(change.field()).isEqualTo("status");
        assertThat(change.mark()).isEqualTo(DocumentChange.Mark.CHANGED);
        assertThat(change.before()).isEqualTo("Paid");
        assertThat(change.after()).isEqualTo("Shipped");
    }

    @Test
    @DisplayName("a list that gained an entry is an addition carrying the entries it gained")
    void reportsAGrownListAsAnAdditionOfTheNewEntries() {
        Map<String, Object> shipment = row("shipment_id", "shp_2", "carrier", "FedEx");
        DocumentDiff diff = DocumentDiff.between(
                row("id", "ord_123", "shipments", List.of(row("shipment_id", "shp_1"))),
                row("id", "ord_123", "shipments", List.of(row("shipment_id", "shp_1"), shipment)));

        assertThat(diff.changes()).hasSize(1);
        DocumentChange change = diff.changes().get(0);
        assertThat(change.field()).isEqualTo("shipments");
        assertThat(change.mark()).isEqualTo(DocumentChange.Mark.ADDED);
        assertThat(change.addedEntries())
                .as("the entries the list gained, so a view can show what arrived rather than the "
                        + "whole list again")
                .containsExactly(shipment);
    }

    @Test
    @DisplayName("a list whose entry was edited in place is a change, not an addition")
    void reportsAnEditedListEntryAsAChange() {
        DocumentDiff diff = DocumentDiff.between(
                row("shipments", List.of(row("state", "label_created"))),
                row("shipments", List.of(row("state", "in_transit"))));

        assertThat(diff.changes()).hasSize(1);
        assertThat(diff.changes().get(0).mark()).isEqualTo(DocumentChange.Mark.CHANGED);
        assertThat(diff.changes().get(0).addedEntries())
                .as("nothing arrived — the same one entry says something else")
                .isEmpty();
    }

    @Test
    @DisplayName("a longer list that no longer holds what it held is a change, not an addition")
    void doesNotCallARewrittenListAnAddition() {
        DocumentDiff diff = DocumentDiff.between(
                row("shipments", List.of("shp_1")),
                row("shipments", List.of("shp_7", "shp_8")));

        assertThat(diff.changes().get(0).mark())
                .as("longer is not the same as grown: reporting this as an addition would show the "
                        + "reader two entries that arrived and never mention the one that went away")
                .isEqualTo(DocumentChange.Mark.CHANGED);
        assertThat(diff.changes().get(0).addedEntries()).isEmpty();
    }

    @Test
    @DisplayName("a field that appeared is an addition and one that went away is a removal")
    void reportsAppearedAndDisappearedFields() {
        DocumentDiff diff = DocumentDiff.between(
                row("id", "ord_123", "coupon", "SUMMER"),
                row("id", "ord_123", "tracking", "1Z999"));

        assertThat(diff.changes()).extracting(DocumentChange::field, DocumentChange::mark)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("coupon", DocumentChange.Mark.REMOVED),
                        org.assertj.core.groups.Tuple.tuple("tracking", DocumentChange.Mark.ADDED));
    }

    @Test
    @DisplayName("two identical rows differ in nothing, and that is the frame a view must not redraw")
    void reportsNoChangeBetweenIdenticalRows() {
        DocumentDiff diff = DocumentDiff.between(
                row("id", "ord_123", "status", "Paid"),
                row("id", "ord_123", "status", "Paid"));

        assertThat(diff.changes()).isEmpty();
        assertThat(diff.isEmpty())
                .as("the no-change answer both views branch on: a redraw with nothing in it is how a "
                        + "reader loses the ability to tell a quiet table from a dead stream")
                .isTrue();
    }

    @Test
    @DisplayName("the first version of a row is every field arriving at once, not a row full of edits")
    void reportsAFirstSightingAsAdditions() {
        DocumentDiff diff = DocumentDiff.between(null, row("id", "ord_123", "status", "Paid"));

        assertThat(diff.changes()).extracting(DocumentChange::mark)
                .containsOnly(DocumentChange.Mark.ADDED);
        assertThat(diff.changes()).extracting(DocumentChange::field)
                .containsExactly("id", "status");
    }

    @Test
    @DisplayName("the fields keep the order the row itself gives them, so a redraw does not reshuffle")
    void keepsTheRowsOwnFieldOrder() {
        DocumentDiff diff = DocumentDiff.between(
                row("a", 1, "b", 2, "c", 3),
                row("a", 9, "b", 8, "c", 7));

        assertThat(diff.changes()).extracting(DocumentChange::field).containsExactly("a", "b", "c");
    }

    @Test
    @DisplayName("the render order holds a departed field in the place the reader last saw it")
    void keepsADepartedFieldInItsOldPlace() {
        DocumentDiff diff = DocumentDiff.between(
                row("id", "ord_123", "coupon", "SUMMER", "status", "Paid"),
                row("id", "ord_123", "status", "Paid", "tracking", "1Z999"));

        assertThat(diff.fields())
                .as("appending a removed field after the new ones would move it away from where it was "
                        + "on screen, which is where a reader looks to find out it is gone")
                .containsExactly("id", "coupon", "status", "tracking");
        assertThat(diff.change("id")).as("an unchanged field reports no difference").isNull();
        assertThat(diff.change("coupon").mark()).isEqualTo(DocumentChange.Mark.REMOVED);
    }

    @Test
    @DisplayName("the summary names each change the way a footer and a stream line both need it")
    void summarizesWhatChanged() {
        DocumentDiff grew = DocumentDiff.between(
                row("shipments", List.of(1)),
                row("shipments", List.of(1, 2)));
        assertThat(grew.summary())
                .as("a list that gained one entry reads as the count it gained")
                .isEqualTo("+1 shipments");

        DocumentDiff edited = DocumentDiff.between(row("status", "Paid"), row("status", "Shipped"));
        assertThat(edited.summary()).isEqualTo("~status");

        DocumentDiff mixed = DocumentDiff.between(
                row("status", "Paid", "coupon", "SUMMER"),
                row("status", "Shipped"));
        assertThat(mixed.summary()).isEqualTo("~status, -coupon");
    }
}
