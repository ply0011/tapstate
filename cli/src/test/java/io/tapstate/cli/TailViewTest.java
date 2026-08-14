package io.tapstate.cli;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What the appended view writes per change, and the memory it keeps to be able to.
 *
 * <p>A change stream does not carry the row as it was. What it carries is the row as it is, so the
 * only "before" this view can honestly show is the version it showed the reader a moment ago — which
 * is a statement about this stream, not a claim about the store's history. Everything here follows
 * from that: a key first seen has no before at all, a key evicted from memory is first seen again,
 * and a field missing from an update is not a field that went away.
 */
class TailViewTest {

    private static Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            row.put((String) pairs[i], pairs[i + 1]);
        }
        return row;
    }

    @Test
    @DisplayName("a key first seen is shown whole, as arriving rather than as edited")
    void firstSightingOfAKeyIsShownWhole() {
        List<String> written = new TailView(8).onChange(
                TailChange.upsert("14:22:11", "ord_123", row("id", "ord_123", "status", "Paid")));

        assertThat(written.get(0)).startsWith("14:22:11  ord_123  ");
        assertThat(written)
                .as("nothing was known about this row a moment ago, so every field of it is news")
                .anySatisfy(line -> assertThat(line).contains("+ status"));
    }

    @Test
    @DisplayName("a second change to the same key is shown against what this stream last showed")
    void laterChangeIsShownAgainstWhatWasLastShown() {
        TailView view = new TailView(8);
        view.onChange(TailChange.upsert("14:22:11", "ord_123", row("id", "ord_123", "status", "Paid")));

        List<String> written = view.onChange(
                TailChange.upsert("14:22:15", "ord_123", row("id", "ord_123", "status", "Shipped")));

        assertThat(written)
                .as("\"Paid\" is what this stream put on screen a moment ago, so showing it as the "
                        + "previous value states something the reader can check — it is not a claim "
                        + "about what the store held, which the stream never says")
                .anySatisfy(line -> assertThat(line).contains("~ status").contains("Paid").contains("Shipped"));
    }

    @Test
    @DisplayName("a field an update did not mention is not reported as a field that went away")
    void doesNotReportAnUnmentionedFieldAsRemoved() {
        TailView view = new TailView(8);
        view.onChange(TailChange.upsert("14:22:11", "ord_123",
                row("id", "ord_123", "status", "Paid", "total", 12)));

        List<String> written = view.onChange(
                TailChange.upsert("14:22:15", "ord_123", row("id", "ord_123", "status", "Shipped")));

        assertThat(written)
                .as("a change stream may carry only the fields an update touched, so an absent field "
                        + "means 'not mentioned', not 'deleted' — reporting removals would announce "
                        + "most of the row as gone on every single update")
                .noneSatisfy(line -> assertThat(line).contains("- total"));
        assertThat(written).anySatisfy(line -> assertThat(line).contains("~ status"));
    }

    @Test
    @DisplayName("a delete says the row went, and does not pretend to know what was in it")
    void deleteIsItsOwnLine() {
        TailView view = new TailView(8);
        view.onChange(TailChange.upsert("14:22:11", "ord_123", row("id", "ord_123", "status", "Paid")));

        List<String> written = view.onChange(TailChange.delete("14:22:20", "ord_123"));

        assertThat(written).isNotEmpty();
        assertThat(written.get(0))
                .startsWith("14:22:20  ord_123  ")
                .contains("deleted");
    }

    @Test
    @DisplayName("a change that changed nothing still gets a line, because the stream reported one")
    void reportsAChangeEvenWhenTheRowLooksTheSame() {
        TailView view = new TailView(8);
        Map<String, Object> same = row("id", "ord_123", "status", "Paid");
        view.onChange(TailChange.upsert("14:22:11", "ord_123", same));

        assertThat(view.onChange(TailChange.upsert("14:22:15", "ord_123", same)))
                .as("the appended view reports events, not state: the store wrote something, and a "
                        + "stream that silently dropped it would be under-reporting what happened — the "
                        + "opposite rule from the in-place view, which reports state and must not repeat")
                .isNotEmpty();
    }

    @Test
    @DisplayName("the memory of what was last shown is bounded, and forgetting is a first sighting again")
    void memoryIsBoundedAndForgettingShowsWhole() {
        TailView view = new TailView(2);
        view.onChange(TailChange.upsert("14:22:01", "a", row("v", 1)));
        view.onChange(TailChange.upsert("14:22:02", "b", row("v", 1)));
        view.onChange(TailChange.upsert("14:22:03", "c", row("v", 1)));

        List<String> written = view.onChange(TailChange.upsert("14:22:04", "a", row("v", 2)));

        assertThat(written)
                .as("a follow on a busy table meets unboundedly many keys, so what it remembers has to "
                        + "be bounded — and a key it has forgotten is one it is seeing for the first "
                        + "time, which is exactly how it renders")
                .anySatisfy(line -> assertThat(line).contains("+ v"))
                .noneSatisfy(line -> assertThat(line).contains("~ v"));
    }

    @Test
    @DisplayName("a key still being changed is kept, while the ones nobody touches are the ones dropped")
    void keepsTheKeysStillBeingChanged() {
        TailView view = new TailView(2);
        view.onChange(TailChange.upsert("14:22:01", "a", row("v", 1)));
        view.onChange(TailChange.upsert("14:22:02", "b", row("v", 1)));
        view.onChange(TailChange.upsert("14:22:03", "a", row("v", 2)));   // a is touched again
        view.onChange(TailChange.upsert("14:22:04", "c", row("v", 1)));   // evicts the coldest, which is b

        assertThat(view.onChange(TailChange.upsert("14:22:05", "a", row("v", 3))))
                .as("dropping by age of last change keeps the rows a reader is actually watching")
                .anySatisfy(line -> assertThat(line).contains("~ v"));
    }
}
