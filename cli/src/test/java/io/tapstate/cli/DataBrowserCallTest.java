package io.tapstate.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.tapstate.cli.DataBrowserCall.Collections;
import io.tapstate.cli.DataBrowserCall.Find;
import io.tapstate.cli.DataBrowserCall.Malformed;
import io.tapstate.cli.DataBrowserCall.Stats;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The read shell's own small language: the three calls a user types at the prompt, and what it does with
 * a line that is nearly one. Nearly-one is the interesting half — a line this shell claims and then
 * cannot parse has to say why, while a line that was never its own has to fall through untouched to the
 * verb table, or every mistyped verb would be answered by the wrong half of the CLI.
 */
class DataBrowserCallTest {

    @Test
    void readsAListingOfEveryDeclaredSource() {
        assertThat(DataBrowserCall.parse("show collections")).isEqualTo(new Collections(null));
    }

    @Test
    void readsAListingNarrowedToOneSource() {
        assertThat(DataBrowserCall.parse("show collections views")).isEqualTo(new Collections("views"));
    }

    @Test
    void readsASizeReadOnANamespace() {
        assertThat(DataBrowserCall.parse("views.order_state.stats()"))
                .isEqualTo(new Stats("views", "order_state"));
    }

    @Test
    void readsAnUnfilteredRead() {
        assertThat(DataBrowserCall.parse("views.order_state.find()"))
                .isEqualTo(new Find("views", "order_state", null, null, null));
    }

    @Test
    void readsATermWrittenWithBareKeysAndDoubleQuotes() {
        // The shell's literal is a JavaScript object rather than strict JSON, because that is what a
        // reader coming from a database shell types. What it spells out is the vocabulary itself, in the
        // same three words every other surface sends.
        assertThat(DataBrowserCall.parse("views.order_state.find({field: \"status\", op: \"eq\", value: \"Paid\"})"))
                .isEqualTo(new Find("views", "order_state",
                        Map.of("field", "status", "op", "eq", "value", "Paid"), null, null));
    }

    @Test
    void readsSingleQuotedTextAsText() {
        assertThat(((Find) DataBrowserCall.parse("v.c.find({field:'a', op:'eq', value:'b'})")).filter())
                .isEqualTo(Map.of("field", "a", "op", "eq", "value", "b"));
    }

    @Test
    void readsNumbersAndBooleansAsThemselvesRatherThanAsText() {
        // A number sent as text is a filter that matches nothing against a numeric field, and reads back
        // as a collection that holds no such rows.
        assertThat(((Find) DataBrowserCall.parse("v.c.find({field:'total', op:'gt', value:100})")).filter())
                .isEqualTo(Map.of("field", "total", "op", "gt", "value", 100L));
        assertThat(((Find) DataBrowserCall.parse("v.c.find({field:'note', op:'exists', value:true})")).filter())
                .isEqualTo(Map.of("field", "note", "op", "exists", "value", true));
    }

    @Test
    void readsACombinationWithItsListOfTerms() {
        assertThat(((Find) DataBrowserCall.parse(
                "v.c.find({all: [{field:'status', op:'eq', value:'Paid'}, {field:'total', op:'gt', value:100}]})"))
                .filter())
                .isEqualTo(Map.of("all", List.of(
                        Map.of("field", "status", "op", "eq", "value", "Paid"),
                        Map.of("field", "total", "op", "gt", "value", 100L))));
    }

    @Test
    void readsTheSizeAndOrderChainedOnAfterTheRead() {
        Find find = (Find) DataBrowserCall.parse(
                "views.orders.find().sort({field:'total', dir:'desc'}).limit(25)");

        assertThat(find.limit()).isEqualTo(25);
        assertThat(find.sort()).isEqualTo(new DataBrowserCall.Order("total", "desc"));
    }

    @Test
    void readsTheChainInEitherOrder() {
        // Neither reads as the primary one, and a shell that accepted only one order would refuse a line
        // that is plainly correct.
        Find find = (Find) DataBrowserCall.parse("views.orders.find().limit(5).sort({field:'a', dir:'asc'})");

        assertThat(find.limit()).isEqualTo(5);
        assertThat(find.sort()).isEqualTo(new DataBrowserCall.Order("a", "asc"));
    }

    @Test
    void keepsTheDotsAfterTheFirstOneWithTheCollection() {
        // A source id is one artifact id, and a collection name may hold dots. Splitting on the last dot
        // instead would read `views.a.b.find()` as a source called `views.a`, which resolves to nothing
        // and blames the wrong half of what the user typed.
        assertThat(DataBrowserCall.parse("views.a.b.stats()")).isEqualTo(new Stats("views", "a.b"));
    }

    @Test
    void leavesALineThatIsNotOneOfItsOwnAlone() {
        // The verb table answers these. Claiming them here would answer a mistyped verb with a filter
        // syntax complaint.
        assertThat(DataBrowserCall.parse("ls")).isNull();
        assertThat(DataBrowserCall.parse("status my-pipeline")).isNull();
        assertThat(DataBrowserCall.parse("apply ./work")).isNull();
        assertThat(DataBrowserCall.parse("")).isNull();
    }

    @Test
    void refusesANamespaceCallItCannotParseRatherThanPassingItOn() {
        assertThat(DataBrowserCall.parse("views.orders.find({field:'a'")).isInstanceOf(Malformed.class);
        assertThat(DataBrowserCall.parse("views.orders.find({field 'a'})")).isInstanceOf(Malformed.class);
    }

    @Test
    void refusesAVerbThatIsNotOneOfTheThree() {
        // `drop` and its neighbours are the reason this is a refusal rather than a fall-through: a read
        // shell that quietly ignored an unknown call would leave the user believing it ran.
        assertThat(DataBrowserCall.parse("views.orders.drop()")).isInstanceOf(Malformed.class);
    }

    @Test
    void namesTheThreeCallsWhenItRefusesAShowItDoesNotKnow() {
        DataBrowserCall parsed = DataBrowserCall.parse("show tables");

        assertThat(parsed).isInstanceOf(Malformed.class);
        assertThat(((Malformed) parsed).reason()).contains("show collections");
    }

    @Test
    void refusesASizeThatIsNotAWholeNumber() {
        assertThat(DataBrowserCall.parse("views.orders.find().limit(all)")).isInstanceOf(Malformed.class);
    }
}
