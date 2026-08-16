package io.tapstate.e2e;

import io.tapstate.core.lifecycle.LifecycleVerb;

import java.util.Map;

/**
 * One stage of a specification. Steps run in declaration order; the order is the scenario.
 *
 * <p>Lifecycle steps are spelled exactly as the product spells them - {@code start}, {@code pause},
 * {@code resume}, {@code stop} - and carry the product's own verb enum, so neither the word nor the
 * value can drift from what the product accepts. {@code run} is deliberately not a step: the
 * product already reserves it for a different meaning, apply-then-start.
 *
 * <p>There is no rewind step: re-snapshotting is the explicit {@code stop} then {@code start} pair,
 * which is exactly what the product's verb set offers.
 */
public sealed interface Step {

    /** Drives one lifecycle verb and returns once the intent is recorded, not once it converges. */
    record Lifecycle(LifecycleVerb verb) implements Step {}

    /** Produces changes against a seeded table while the pipeline is running. */
    record Cdc(TableAlias table, Change change) implements Step {}

    /**
     * What one cdc step does to the table it names. Two shapes, and the difference is whether the
     * specification decides which rows move.
     *
     * <p>{@link Generated} asks for a number of changes and leaves the rest to the driver, which is
     * enough whenever the case is about a count arriving. {@link Update} and {@link Delete} name the
     * row and, for an update, the value - which is what a case about an assembled document needs,
     * because the thing it has to read back is a field, not a total. A count is satisfied by changing
     * any row; only a named row and a named value can hold an implementation to changing the right one.
     *
     * <p>There is deliberately no valued insert. The generated form already inserts, and no case here
     * needs an inserted row to carry chosen values; adding one would widen the surface without a
     * witness behind it. It is an addition, not an omission to be tidied up later.
     */
    sealed interface Change {

        /** A number of changes of one kind, with the driver choosing which rows move. */
        record Generated(CdcOp op, long rows) implements Change {}

        /** Sets columns on the one row the settings locate. */
        record Update(Map<String, Object> where, Map<String, Object> set) implements Change {

            public Update {
                where = Map.copyOf(where);
                set = Map.copyOf(set);
            }
        }

        /** Removes the one row the settings locate. */
        record Delete(Map<String, Object> where) implements Change {

            public Delete {
                where = Map.copyOf(where);
            }
        }
    }

    /** Polls a matcher until it holds or the bound expires. */
    record Await(Matcher matcher) implements Step {}

    /** Checks a matcher once, now. */
    record Assertion(Matcher matcher) implements Step {}
}
