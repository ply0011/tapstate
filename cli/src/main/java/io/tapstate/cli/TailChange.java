package io.tapstate.cli;

import java.util.Map;

/**
 * One change the followed stream delivered, as it arrived: what the store did, the row on either side
 * of it as far as the connector supplied them, and when.
 *
 * <p>Either row may be absent, and absent is a fact rather than a gap to fill. An alteration whose
 * {@code before} is missing is one the store did not describe that way; a removal carrying only a key
 * is a removal the store described only that far.
 */
record TailChange(Kind kind, String at, Map<String, Object> before, Map<String, Object> after) {

    /** What the store did. */
    enum Kind {
        INSERT,
        UPDATE,
        DELETE
    }
}
