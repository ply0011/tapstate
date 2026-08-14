package io.tapstate.spi.store;

import java.util.List;
import java.util.Objects;

/**
 * Which rows one read matches, in a vocabulary of its own: a term is a field, an operator and a value,
 * and terms combine one level deep. Neutral by construction — it names no backend's query language, so
 * the bridge driving a particular connector is what turns it into whatever that connector expects.
 *
 * <p>The nesting stops at one level by shape rather than by a check: a combination holds terms, not
 * further combinations, so an arbitrarily deep boolean expression cannot be built at all. That bound is
 * the point. This is a read face that previews rows, and every operator it does not have is an operator
 * no caller can reach — including the ones that run code inside the database.
 *
 * <p>A null filter is not a member of this type and means every row, which is a request rather than a
 * gap. What a value may be follows from its operator, and a value that does not fit is a caller mistake
 * rather than a user's: the surface that accepts a request refuses a malformed one with a code, so
 * anything arriving here has already been checked and a violation is a defect above.
 */
public sealed interface DataBrowserFilter {

    /**
     * What a conjunction may hold: one term, or one alternative between terms. Nothing else — so the
     * deepest expression writable is a conjunction of alternatives of terms, and no fourth level exists
     * to write. That is the shape a reader actually types (several conditions, one of them a choice); an
     * arbitrarily nested boolean expression is a query rather than a preview.
     */
    sealed interface Conjunct extends DataBrowserFilter {
    }

    /** One field tested against one value. {@code field} may be a dot path, since documents nest. */
    record Match(String field, Operator operator, Object value) implements Conjunct {

        public Match {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(operator, "operator");
            // Absent and null are the same thing over a wire format that has one word for both, so this
            // face never offers a term that turns on the difference.
            Objects.requireNonNull(value, "value");
            operator.requireUsableValue(value);
        }
    }

    /** Every one of these has to hold; each is a term or an alternative between terms. */
    record All(List<Conjunct> terms) implements DataBrowserFilter {

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

    /**
     * How one term tests its field. Each carries what kind of value it takes, because that is the half a
     * caller gets wrong: a membership test needs a set and a presence test needs a yes or no, and neither
     * mistake is visible in the term's shape.
     */
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

        void requireUsableValue(Object value) {
            switch (this) {
                case IN -> require(value instanceof List<?> set && !set.isEmpty(),
                        "in takes a non-empty list of values");
                case EXISTS -> require(value instanceof Boolean, "exists takes true or false");
                case CONTAINS -> require(value instanceof String, "contains takes text");
                default -> {
                }
            }
        }

        private void require(boolean holds, String what) {
            if (!holds) {
                throw new IllegalArgumentException(what);
            }
        }
    }

    private static <T> List<T> requireAtLeastOne(List<T> terms) {
        Objects.requireNonNull(terms, "terms");
        List<T> copy = List.copyOf(terms);
        if (copy.isEmpty()) {
            // An empty combination reads as every row, which is what an absent filter already says. Two
            // spellings of one request is how a surface comes to have two answers for it.
            throw new IllegalArgumentException("a combination needs at least one term");
        }
        return copy;
    }
}
