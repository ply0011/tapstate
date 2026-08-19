package io.tapstate.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The read shell's filter is written the way a reader writes one in a database shell — that is the whole
 * point of using their syntax rather than ours. What it is <em>not</em> is a document forwarded to that
 * database: it is read here and translated into Tapstate's own vocabulary, so the request that leaves
 * this process says only what the vocabulary can say.
 *
 * <p>That split is what these cases pin from both sides. The shapes a reader actually types come through
 * as the request they mean; the operators that only a passthrough could carry are refused by name, and
 * the refusal says what to write instead — a filter language nobody can discover is not one they can use.
 */
class MongoShellFilterTest {

    private static Object translate(String written) {
        DataBrowserCall call = DataBrowserCall.parse("v.c.find(" + written + ")");
        assertThat(call).isInstanceOf(DataBrowserCall.Find.class);
        return ((DataBrowserCall.Find) call).filter();
    }

    @Test
    void readsABareValueAsEquality() {
        assertThat(translate("{order_id: \"ord_123\"}"))
                .isEqualTo(Map.of("field", "order_id", "op", "eq", "value", "ord_123"));
    }

    @Test
    void readsAnOperatorDocumentAsThatOperator() {
        assertThat(translate("{age: {$gt: 12}}"))
                .isEqualTo(Map.of("field", "age", "op", "gt", "value", 12L));
    }

    @Test
    void readsSeveralFieldsAsAConjunctionTheWayAShellDoes() {
        // Side by side in one document means "and" in every shell a reader has used. Read as anything
        // else it would silently answer a different question than the one on screen.
        assertThat(translate("{status: \"Paid\", total: {$gte: 100}}"))
                .isEqualTo(Map.of("all", List.of(
                        Map.of("field", "status", "op", "eq", "value", "Paid"),
                        Map.of("field", "total", "op", "gte", "value", 100L))));
    }

    @Test
    void readsAnAlternativeAsAnAlternative() {
        assertThat(translate("{$or: [{status: \"0\"}, {status: \"1\"}]}"))
                .isEqualTo(Map.of("any", List.of(
                        Map.of("field", "status", "op", "eq", "value", "0"),
                        Map.of("field", "status", "op", "eq", "value", "1"))));
    }

    @Test
    void readsAConjunctionThatCarriesAnAlternativeInsideIt() {
        // The shape from the request that set this syntax: two conditions and a choice, in one document.
        // It is the common one, so a translation that cannot carry it is a translation nobody can use.
        assertThat(translate("{id: 1, age: {$gt: 12}, $or: [{status: '0'}, {status: '1'}]}"))
                .isEqualTo(Map.of("all", List.of(
                        Map.of("field", "id", "op", "eq", "value", 1L),
                        Map.of("field", "age", "op", "gt", "value", 12L),
                        Map.of("any", List.of(
                                Map.of("field", "status", "op", "eq", "value", "0"),
                                Map.of("field", "status", "op", "eq", "value", "1"))))));
    }

    @Test
    void readsAnExplicitConjunctionToo() {
        assertThat(translate("{$and: [{a: 1}, {b: 2}]}"))
                .isEqualTo(Map.of("all", List.of(
                        Map.of("field", "a", "op", "eq", "value", 1L),
                        Map.of("field", "b", "op", "eq", "value", 2L))));
    }

    @Test
    void readsTheSetAndPresenceOperators() {
        assertThat(translate("{status: {$in: [\"Paid\", \"Shipped\"]}}"))
                .isEqualTo(Map.of("field", "status", "op", "in", "value", List.of("Paid", "Shipped")));
        assertThat(translate("{note: {$exists: false}}"))
                .isEqualTo(Map.of("field", "note", "op", "exists", "value", false));
    }

    @Test
    void readsADotPathAsTheFieldItNames() {
        assertThat(translate("{\"shipments.carrier\": \"FedEx\"}"))
                .isEqualTo(Map.of("field", "shipments.carrier", "op", "eq", "value", "FedEx"));
    }

    @Test
    void refusesTheOperatorsThatRunCodeInTheDatabase() {
        // The reason this is a translation and not a passthrough. Each of these evaluates an expression
        // inside the store, and no preview needs one; a shell that forwarded the document would carry
        // every one of them without ever deciding to.
        for (String hostile : List.of("{$where: \"this.a == 1\"}",
                "{$expr: {$eq: [1, 1]}}",
                "{a: {$function: {body: \"f\"}}}")) {
            assertThat(DataBrowserCall.parse("v.c.find(" + hostile + ")"))
                    .as("%s must not reach the store", hostile)
                    .isInstanceOf(DataBrowserCall.Malformed.class);
        }
    }

    @Test
    void refusesAPatternAndNamesTheLiteralSearchInstead() {
        // A pattern is the one thing the vocabulary deliberately cannot express, so `$regex` cannot be
        // honoured. Refusing without naming `$contains` would leave a reader with no way to search text
        // at all, which reads as the feature being missing rather than narrowed.
        DataBrowserCall call = DataBrowserCall.parse("v.c.find({carrier: {$regex: \"Fed.*\"}})");

        assertThat(call).isInstanceOf(DataBrowserCall.Malformed.class);
        assertThat(((DataBrowserCall.Malformed) call).reason()).contains("$contains");
    }

    @Test
    void readsTheLiteralSearchTapstateAddsForIt() {
        assertThat(translate("{carrier: {$contains: \"Fed\"}}"))
                .isEqualTo(Map.of("field", "carrier", "op", "contains", "value", "Fed"));
    }

    @Test
    void refusesAnOperatorItDoesNotKnowAndSaysWhichOnesItDoes() {
        DataBrowserCall call = DataBrowserCall.parse("v.c.find({a: {$mod: [4, 0]}})");

        assertThat(call).isInstanceOf(DataBrowserCall.Malformed.class);
        assertThat(((DataBrowserCall.Malformed) call).reason()).contains("$mod").contains("$gt");
    }

    @Test
    void refusesAnAlternativeNestedInsideAnAlternative() {
        // The bound the vocabulary holds by shape, at the one boundary where a deeper one is writable.
        assertThat(DataBrowserCall.parse("v.c.find({$or: [{$or: [{a: 1}]}, {b: 2}]})"))
                .isInstanceOf(DataBrowserCall.Malformed.class);
    }

    @Test
    void refusesAFieldTestedByMoreThanOneOperatorAtOnce() {
        // `{age: {$gt: 1, $lt: 9}}` is a range in a shell, and the vocabulary has one operator per term.
        // Honouring only the first would answer an unbounded range and look like it worked.
        DataBrowserCall call = DataBrowserCall.parse("v.c.find({age: {$gt: 1, $lt: 9}})");

        assertThat(call).isInstanceOf(DataBrowserCall.Malformed.class);
        assertThat(((DataBrowserCall.Malformed) call).reason()).contains("$and");
    }

    @Test
    void refusesANullMemberOfAConjunctionRatherThanCrashing() {
        Map<String, Object> written = new LinkedHashMap<>();
        written.put("$and", java.util.Arrays.asList((Object) null));

        assertThatThrownBy(() -> MongoShellFilter.translate(written))
                .isInstanceOf(DataBrowserCall.Unreadable.class)
                .hasMessageContaining("`null`");
    }
}
