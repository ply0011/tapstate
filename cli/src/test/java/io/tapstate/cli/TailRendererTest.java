package io.tapstate.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The appended view shows each change as the connector supplied it, and works nothing out.
 *
 * <p>What is pinned here is mostly absence: a row the change did not carry does not appear, and does
 * not appear as empty either. A view that filled those in would be showing its own history as the
 * store's, and a reader has no way to tell whose it is.
 */
class TailRendererTest {

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    @Test
    @DisplayName("an insert shows the row that arrived, and nothing on the other side")
    void insertShowsOnlyTheRowThatArrived() {
        List<String> lines = TailRenderer.lines(new TailChange(
                TailChange.Kind.INSERT, "14:22:11", null, row("id", "ord_1", "status", "Paid")));

        assertThat(lines.get(0)).isEqualTo("14:22:11  insert");
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("after").contains("ord_1"));
        assertThat(lines)
                .as("there was no earlier version of a row that has just arrived, and printing an "
                        + "empty one would say the connector supplied a row with nothing in it")
                .noneSatisfy(line -> assertThat(line).contains("before"));
    }

    @Test
    @DisplayName("an update shows both sides when the change carried both")
    void updateShowsBothSidesWhenItHasThem() {
        List<String> lines = TailRenderer.lines(new TailChange(
                TailChange.Kind.UPDATE, "14:22:15",
                row("id", "ord_1", "status", "Paid"),
                row("id", "ord_1", "status", "Shipped")));

        assertThat(lines.get(0)).isEqualTo("14:22:15  update");
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("before").contains("Paid"));
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("after").contains("Shipped"));
    }

    @Test
    @DisplayName("an update that carried no earlier row shows only the later one")
    void updateWithoutABeforeShowsOnlyTheAfter() {
        // The common case against a store whose change stream does not keep earlier versions. The view
        // says what it was given; inventing the missing side is the one thing it must not do.
        List<String> lines = TailRenderer.lines(new TailChange(
                TailChange.Kind.UPDATE, "14:22:15", null, row("id", "ord_1", "status", "Shipped")));

        assertThat(lines.get(0)).isEqualTo("14:22:15  update");
        assertThat(lines).hasSize(2);
        assertThat(lines).noneSatisfy(line -> assertThat(line).contains("before"));
    }

    @Test
    @DisplayName("a removal shows as much of the row as the change carried, however little that is")
    void deleteShowsWhateverCameWithIt() {
        List<String> lines = TailRenderer.lines(new TailChange(
                TailChange.Kind.DELETE, "14:22:20", row("_id", "64f0"), null));

        assertThat(lines.get(0)).isEqualTo("14:22:20  delete");
        assertThat(lines).anySatisfy(line -> assertThat(line).contains("before").contains("64f0"));
        assertThat(lines).noneSatisfy(line -> assertThat(line).contains("after"));
    }

    @Test
    @DisplayName("a change that carried no row at all is still an event")
    void aChangeWithNoRowIsStillReported() {
        List<String> lines = TailRenderer.lines(
                new TailChange(TailChange.Kind.DELETE, "14:22:20", null, null));

        assertThat(lines)
                .as("the store did something, and a follow that dropped it because there was nothing "
                        + "to print would be under-reporting what happened")
                .containsExactly("14:22:20  delete");
    }

    @Test
    @DisplayName("every event starts at column zero with its time, so the stream stays pipeable")
    void eventsStartAtColumnZero() {
        List<String> lines = TailRenderer.lines(new TailChange(
                TailChange.Kind.UPDATE, "14:22:15", row("a", 1), row("a", 2)));

        assertThat(lines.get(0)).doesNotStartWith(" ");
        assertThat(lines.subList(1, lines.size()))
                .as("the rows are told apart from the event by position, not by punctuation a search "
                        + "would have to know about")
                .allSatisfy(line -> assertThat(line).startsWith("  "));
    }
}
