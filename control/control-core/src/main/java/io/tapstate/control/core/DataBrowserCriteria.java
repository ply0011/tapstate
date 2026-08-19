package io.tapstate.control.core;

import io.tapstate.core.common.TapstateException;
import io.tapstate.spi.store.DataBrowserFilter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Which rows one read matches, in the control ring's own vocabulary: a term is a field, an operator and
 * a value, and terms combine one level deep. The surfaces send this; the service translates it into the
 * storage-port filter, so no face reaches into the ports to say which rows it wants.
 *
 * <p>It is a vocabulary rather than a pass-through of the store's own query language, and that is the
 * whole of its design. A read face that forwarded a backend query document would forward everything that
 * language can express — including its operators that evaluate code inside the database — and would offer
 * an agent a shapeless value with a sentence of prose for a specification. Here every request has a form
 * that can be described, checked, and translated to a second backend without any surface changing.
 *
 * <p>The bound is deliberate and is enforced by shape: a combination holds terms, not further
 * combinations, so an arbitrarily nested boolean expression cannot be written down. What that costs is
 * real and is not an oversight — patterns, element-wise matching on arrays, negated groups. A preview
 * face does not need them.
 *
 * <p>Absent — a null filter on the request — is not the same as any value here. It means every row, and
 * it is the only shape for which a total is offered.
 */
public sealed interface DataBrowserCriteria {

    /**
     * What a conjunction may hold: one term, or one alternative between terms. Nothing else — so the
     * deepest expression writable is a conjunction of alternatives of terms, and no fourth level exists
     * to write. That is the shape a reader actually types (several conditions, one of them a choice); an
     * arbitrarily nested boolean expression is a query rather than a preview.
     */
    sealed interface Conjunct extends DataBrowserCriteria {
    }

    /** One field tested against one value. {@code field} may be a dot path, since documents nest. */
    record Match(String field, Operator operator, Object value) implements Conjunct {

        public Match {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(value, "value");
            // The spelling, before anything reads it: a malformed escape refuses bare below this ring,
            // and there is no road from a bare refusal here to anything a caller can be told.
            ReadableField.of(field);
            operator.requireUsable(field, value);
        }
    }

    /** Every one of these has to hold; each is a term or an alternative between terms. */
    record All(List<Conjunct> terms) implements DataBrowserCriteria {

        public All {
            terms = requireAtLeastOne(terms);
        }
    }

    /** At least one term has to hold. Terms only — an alternative of alternatives says nothing more. */
    record Any(List<Match> terms) implements Conjunct {

        public Any {
            terms = requireAtLeastOne(terms);
        }
    }

    /** How one term tests its field — the whole vocabulary, and nothing reaches a store outside it. */
    enum Operator {
        EQ,
        NE,
        GT,
        GTE,
        LT,
        LTE,
        /** The field is one of the values listed. */
        IN,
        /** The field is present, or is absent — the value says which. */
        EXISTS,
        /** The field's text holds the value's text somewhere inside it. */
        CONTAINS;

        /** How this operator is spelt in a request, and in the refusal that names it back. */
        String spelling() {
            return name().toLowerCase(Locale.ROOT);
        }

        /**
         * Refuses a value this operator cannot use. Three of the nine take a value of a particular kind,
         * and none of those mistakes is visible in the answer: a membership test given one string, or a
         * presence test given the word "true", reaches the store as a test that matches nothing — an
         * empty read, indistinguishable from a collection that holds no such rows.
         *
         * <p>Coded rather than a bare crash because this is user input: it arrives as JSON, where the
         * type of a value is whatever was typed, and no surface can be relied on to have checked it.
         */
        void requireUsable(String field, Object value) {
            switch (this) {
                case IN -> require(value instanceof List<?> set && !set.isEmpty(),
                        field, "takes a non-empty list of values");
                case EXISTS -> require(value instanceof Boolean, field, "takes true or false");
                case CONTAINS -> require(value instanceof String, field, "takes text");
                default -> {
                }
            }
        }

        private void require(boolean holds, String field, String what) {
            if (!holds) {
                throw new TapstateException(
                        ControlError.MALFORMED_REQUEST,
                        Map.of("reason", "`" + spelling() + "` on `" + field + "` " + what),
                        null);
            }
        }

        DataBrowserFilter.Operator toPortRequest() {
            return switch (this) {
                case EQ -> DataBrowserFilter.Operator.EQ;
                case NE -> DataBrowserFilter.Operator.NE;
                case GT -> DataBrowserFilter.Operator.GT;
                case GTE -> DataBrowserFilter.Operator.GTE;
                case LT -> DataBrowserFilter.Operator.LT;
                case LTE -> DataBrowserFilter.Operator.LTE;
                case IN -> DataBrowserFilter.Operator.IN;
                case EXISTS -> DataBrowserFilter.Operator.EXISTS;
                case CONTAINS -> DataBrowserFilter.Operator.CONTAINS;
            };
        }
    }

    /**
     * Whether {@code row} satisfies these criteria, evaluated here rather than by the store.
     *
     * <p>A followed stream needs this and a bounded read does not: a change capture is asked for a
     * table, not for a query, so a follower's filter cannot travel with it. What it narrows is what
     * the reader is shown; everything the table changes is still captured, and still costs what it
     * costs.
     *
     * <p>This is therefore the vocabulary's second implementation, beside the one that translates it
     * into a store's own dialect, and the two agreeing is what makes one filter mean one thing. Two
     * rules are the ones that could most easily part: a term against a list holds when any entry
     * satisfies it (which is the store's own rule, not a convenience), and the one operator taking
     * free text takes it literally, because the translation escapes that text whole.
     */
    default boolean matches(Map<String, Object> row) {
        return switch (this) {
            case Match match -> CriteriaMatcher.matches(match, row);
            case Any any -> any.terms().stream().anyMatch(term -> term.matches(row));
            case All all -> all.terms().stream().allMatch(term -> term.matches(row));
        };
    }

    /** The storage-port filter for these criteria. */
    default DataBrowserFilter toPortRequest() {
        return switch (this) {
            case Match match -> port(match);
            case Any any -> new DataBrowserFilter.Any(terms(any.terms()));
            case All all -> new DataBrowserFilter.All(conjuncts(all.terms()));
        };
    }

    private static DataBrowserFilter.Match port(Match match) {
        return new DataBrowserFilter.Match(
                match.field(), match.operator().toPortRequest(), match.value());
    }

    private static List<DataBrowserFilter.Match> terms(List<Match> terms) {
        List<DataBrowserFilter.Match> translated = new ArrayList<>(terms.size());
        terms.forEach(term -> translated.add(port(term)));
        return translated;
    }

    private static List<DataBrowserFilter.Conjunct> conjuncts(List<Conjunct> members) {
        List<DataBrowserFilter.Conjunct> translated = new ArrayList<>(members.size());
        // Each member translated as itself: an alternative inside a conjunction stays an alternative.
        // Flattened into the conjunction it would read as `a AND b AND c` where `a AND (b OR c)` was
        // asked for -- a stricter filter that still returns rows, so nothing downstream reports it.
        members.forEach(member -> translated.add((DataBrowserFilter.Conjunct) member.toPortRequest()));
        return translated;
    }

    private static <T> List<T> requireAtLeastOne(List<T> terms) {
        List<T> copy = List.copyOf(Objects.requireNonNull(terms, "terms"));
        if (copy.isEmpty()) {
            throw new TapstateException(
                    ControlError.MALFORMED_REQUEST,
                    Map.of("reason", "a filter combination needs at least one term"),
                    null);
        }
        return copy;
    }
}
