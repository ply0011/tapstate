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
    void readsALiteralWrittenWithBareKeysAndDoubleQuotes() {
        // The shell's literal is a JavaScript object rather than strict JSON, because that is what a
        // reader coming from a database shell types. What it means is settled elsewhere; what is pinned
        // here is that the two spellings a reader reaches for both lex.
        assertThat(DataBrowserCall.parse("views.order_state.find({status: \"Paid\"})"))
                .isEqualTo(new Find("views", "order_state",
                        Map.of("field", "status", "op", "eq", "value", "Paid"), null, null));
    }

    @Test
    void readsSingleQuotedTextAsText() {
        assertThat(((Find) DataBrowserCall.parse("v.c.find({a: 'b'})")).filter())
                .isEqualTo(Map.of("field", "a", "op", "eq", "value", "b"));
    }

    @Test
    void readsNumbersAndBooleansAsThemselvesRatherThanAsText() {
        // A number sent as text is a filter that matches nothing against a numeric field, and reads back
        // as a collection that holds no such rows.
        assertThat(((Find) DataBrowserCall.parse("v.c.find({total: {$gt: 100}})")).filter())
                .isEqualTo(Map.of("field", "total", "op", "gt", "value", 100L));
        assertThat(((Find) DataBrowserCall.parse("v.c.find({note: {$exists: true}})")).filter())
                .isEqualTo(Map.of("field", "note", "op", "exists", "value", true));
    }

    @Test
    void readsAListLiteralAsAList() {
        assertThat(((Find) DataBrowserCall.parse("v.c.find({status: {$in: ['Paid', 'Shipped']}})")).filter())
                .isEqualTo(Map.of("field", "status", "op", "in", "value", List.of("Paid", "Shipped")));
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

    @Test
    void readsALiveViewsNamespaceAndItsOptionalFilter() {
        DataBrowserCall bare = DataBrowserCall.parseLive("watch", "views.orders");

        assertThat(bare).isInstanceOf(DataBrowserCall.Live.class);
        assertThat(((DataBrowserCall.Live) bare).sourceId()).isEqualTo("views");
        assertThat(((DataBrowserCall.Live) bare).collection()).isEqualTo("orders");
        assertThat(((DataBrowserCall.Live) bare).filter())
                .as("no filter is not an empty filter — the view is being asked for whatever comes first")
                .isNull();
    }

    @Test
    void readsALiveViewsFilterAsTheVocabularyRatherThanAsItWasTyped() {
        DataBrowserCall parsed = DataBrowserCall.parseLive("tail", "views.orders {status: \"Paid\"}");

        assertThat(parsed).isInstanceOf(DataBrowserCall.Live.class);
        assertThat(((DataBrowserCall.Live) parsed).filter())
                .as("the shell's syntax is read here and rebuilt as the vocabulary, so what leaves this "
                        + "process says only what the vocabulary can say — the same rule a read follows")
                .isEqualTo(Map.of("field", "status", "op", "eq", "value", "Paid"));
    }

    @Test
    void splitsALiveViewsNamespaceAtItsFirstDot() {
        DataBrowserCall parsed = DataBrowserCall.parseLive("watch", "views.a.b");

        assertThat(((DataBrowserCall.Live) parsed).sourceId()).isEqualTo("views");
        assertThat(((DataBrowserCall.Live) parsed).collection())
                .as("a source id is one artifact id while a collection name may hold dots; splitting the "
                        + "other way looks up a source that does not exist and blames the half that was right")
                .isEqualTo("a.b");
    }

    @Test
    void refusesALiveViewWithNoNamespaceAndSaysWhatOneLooksLike() {
        assertThat(DataBrowserCall.parseLive("watch", "")).isInstanceOf(Malformed.class);

        DataBrowserCall noCollection = DataBrowserCall.parseLive("watch", "views");
        assertThat(noCollection).isInstanceOf(Malformed.class);
        assertThat(((Malformed) noCollection).reason())
                .as("a refusal that does not show the shape leaves the reader guessing at it")
                .contains("<source>.<collection>");
    }

    @Test
    void refusesALiveViewFilterTheVocabularyCannotSay() {
        DataBrowserCall parsed = DataBrowserCall.parseLive("tail", "views.orders {status: {$regex: \"^P\"}}");

        assertThat(parsed)
                .as("a live view takes the same filter a read does, so it refuses the same things — and "
                        + "a filter silently dropped would leave the view showing the whole table")
                .isInstanceOf(Malformed.class);
        assertThat(((Malformed) parsed).reason()).contains("$contains");
    }

    @Test
    void aReadAndAFollowTakeTheSameFilterWrittenTheSameWay() {
        // One filter language for the whole face. The two verbs read it through the same translator, so
        // a reader who knows how to narrow a read knows how to narrow a follow -- and a filter that
        // means one thing in one of them cannot come to mean another in the other.
        Object read = ((DataBrowserCall.Find) DataBrowserCall.parse(
                "views.orders.find({status: \"Paid\", age: {$gt: 12}})")).filter();
        Object followed = ((DataBrowserCall.Live) DataBrowserCall.parseLive(
                "tail", "views.orders {status: \"Paid\", age: {$gt: 12}}")).filter();

        assertThat(followed)
                .as("the same words, translated into the same request; two grammars for one face is "
                        + "how the same written filter starts answering differently depending on which "
                        + "verb was asked")
                .isEqualTo(read);
    }

    @Test
    void aReadAndAFollowRefuseTheSameThingsForTheSameReason() {
        DataBrowserCall read = DataBrowserCall.parse("views.orders.find({status: {$regex: \"^P\"}})");
        DataBrowserCall followed = DataBrowserCall.parseLive("tail", "views.orders {status: {$regex: \"^P\"}}");

        assertThat(read).isInstanceOf(Malformed.class);
        assertThat(followed).isInstanceOf(Malformed.class);
        assertThat(((Malformed) followed).reason())
                .as("and the refusal names the same alternative, since it is the same vocabulary")
                .isEqualTo(((Malformed) read).reason());
    }
}
