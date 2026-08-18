package io.tapstate.control.core;

import io.tapstate.spi.store.FieldPath;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Evaluates one term of the read vocabulary against a row held in memory.
 *
 * <p>Split out of the criteria themselves because it is the one part of them that is not a
 * description of a request: the criteria say what was asked for, this says whether a particular row
 * answers it. It exists at all because a followed stream cannot send its filter to the store.
 *
 * <p>Three rules here are the ones a second implementation of a filter language gets wrong, and each
 * is wrong in the direction nobody notices — a row shown that should not have been, or withheld with
 * no sign it was:
 *
 * <ul>
 *   <li>A term against a list holds when <em>any</em> entry satisfies it. That is the store's own
 *       rule for a value against an array, not a convenience added here.
 *   <li>An absent field satisfies nothing but the test for its presence. In particular it does not
 *       satisfy "is not that value": absent is not some other value, and answering otherwise shows
 *       the reader rows that have nothing to do with the field they named.
 *   <li>Values of kinds that have no order between them answer false rather than being ordered by
 *       some rule of this evaluator's own.
 * </ul>
 */
final class CriteriaMatcher {

    private CriteriaMatcher() {
    }

    static boolean matches(DataBrowserCriteria.Match term, Map<String, Object> row) {
        List<Object> found = resolve(row, term.field());
        if (term.operator() == DataBrowserCriteria.Operator.EXISTS) {
            return found.isEmpty() != Boolean.TRUE.equals(term.value());
        }
        if (found.isEmpty()) {
            return false;
        }
        // Any one of the values found under the path satisfying the term is the whole term satisfied:
        // a path that crossed a list resolves to one value per entry.
        return found.stream().anyMatch(value -> holds(term.operator(), value, term.value()));
    }

    private static boolean holds(DataBrowserCriteria.Operator operator, Object value, Object wanted) {
        return switch (operator) {
            case EQ -> equal(value, wanted);
            case NE -> !equal(value, wanted);
            case IN -> wanted instanceof List<?> set && set.stream().anyMatch(one -> equal(value, one));
            case CONTAINS -> value instanceof String text && wanted instanceof String part && text.contains(part);
            case GT -> compare(value, wanted) > 0;
            case GTE -> compare(value, wanted) >= 0;
            case LT -> compare(value, wanted) < 0 && compare(value, wanted) != NO_ORDER;
            case LTE -> compare(value, wanted) <= 0 && compare(value, wanted) != NO_ORDER;
            case EXISTS -> true;   // settled before a value is ever looked at
        };
    }

    /**
     * Whether two values are the same value. Numbers are read on the one number line the ordered
     * operators below already read them on: which Java class a literal parsed into says how it was
     * spelt and how it was transported, never what the reader asked about. A filter whose value came
     * from JSON text holds a whole number as a long; the row it is tested against holds whatever the
     * connector produced, and a 32-bit column produces an int. Answering by type there is a filter
     * that matches nothing at all, with nothing to report it -- the reader watches a table that
     * appears never to change, and the negated form shows every row instead.
     *
     * <p>Two whole numbers are compared exactly rather than through a double. Past 53 bits of
     * mantissa distinct integers round to the same double, and the two mistakes are not equally
     * cheap: a slightly wrong ordering is invisible, while a wrong equality puts another row's data
     * in front of the reader as though they had asked for it.
     */
    private static boolean equal(Object value, Object wanted) {
        if (value instanceof Number left && wanted instanceof Number right) {
            return whole(left) && whole(right)
                    ? new BigInteger(left.toString()).equals(new BigInteger(right.toString()))
                    : left.doubleValue() == right.doubleValue();
        }
        return Objects.equals(value, wanted);
    }

    /** Whether a number has no fractional part to lose, in the type it arrived as. */
    private static boolean whole(Number number) {
        return number instanceof Integer || number instanceof Long
                || number instanceof Short || number instanceof Byte
                || number instanceof BigInteger;
    }

    /**
     * A sentinel that no real comparison returns, so "these two have no order" is distinguishable from
     * "the first is smaller". Ordinary comparators have nowhere to say the former, which is how a
     * mixed pair ends up ordered by whichever rule the code happened to reach first.
     */
    private static final int NO_ORDER = Integer.MIN_VALUE;

    /** The order between two values, or {@link #NO_ORDER} when their kinds have none. */
    private static int compare(Object value, Object wanted) {
        if (value instanceof Number left && wanted instanceof Number right) {
            // One number line rather than one per Java type: which class a JSON literal parsed into is
            // an accident of how it was spelt, and the reader did not mean to ask about it.
            return Double.compare(left.doubleValue(), right.doubleValue());
        }
        if (value instanceof String left && wanted instanceof String right) {
            return left.compareTo(right);
        }
        if (value instanceof Boolean left && wanted instanceof Boolean right) {
            return Boolean.compare(left, right);
        }
        return NO_ORDER;
    }

    /**
     * Every value reachable under a path: one per entry where the path crosses a list, none when the
     * path does not resolve. A list met at the end of the path resolves to the list itself as well as
     * to its entries, so a term can be written against either.
     *
     * <p>The steps are read by the shared parser rather than split here. Splitting on every dot cannot
     * reach a column whose own name holds one, and a second reading of the spelling is how this
     * evaluator and the one that translates into a backend's dialect come to disagree.
     */
    private static List<Object> resolve(Map<String, Object> row, String path) {
        List<Object> current = new ArrayList<>();
        current.add(row);
        for (String segment : FieldPath.of(path).segments()) {
            List<Object> next = new ArrayList<>();
            for (Object holder : current) {
                for (Object step : flatten(holder)) {
                    if (step instanceof Map<?, ?> map && map.containsKey(segment)) {
                        next.add(map.get(segment));
                    }
                }
            }
            current = next;
        }
        List<Object> resolved = new ArrayList<>();
        current.forEach(value -> resolved.addAll(flatten(value)));
        return resolved;
    }

    /** A value, plus its entries when it is a list — the shape a term is offered to match against. */
    private static List<Object> flatten(Object value) {
        if (!(value instanceof List<?> entries)) {
            List<Object> single = new ArrayList<>(1);
            single.add(value);
            return single;
        }
        List<Object> spread = new ArrayList<>(entries.size() + 1);
        spread.addAll(entries);
        spread.add(entries);
        return spread;
    }
}
