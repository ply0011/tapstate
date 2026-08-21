package io.tapstate.control.core;

import io.tapstate.core.common.TapstateException;
import io.tapstate.spi.store.FieldPath;

import java.util.Map;

/**
 * Reads a field spelling a caller wrote, refusing a malformed one with a code.
 *
 * <p>{@link FieldPath} refuses bare, deliberately: it sits below the ring that owns user-facing codes,
 * so the surface that accepted the request is what turns a rejected spelling into one. This is that
 * turn, and it is needed on both roads a spelling travels — the filter on a read and the order asked
 * for beside it — because a spelling that reaches a store went down one of the two.
 *
 * <p>Without it the refusal arrives as a bare unchecked failure, which no handler maps: on a read that
 * is a 500 for something the caller typed, and on a follow it is thrown inside the change callback,
 * where the reader sees a stream that simply stops.
 */
final class ReadableField {

    private ReadableField() {
    }

    /** The path {@code field} spells, or a coded refusal quoting what is wrong with the spelling. */
    static FieldPath of(String field) {
        try {
            return FieldPath.of(field);
        } catch (IllegalArgumentException malformed) {
            throw new TapstateException(
                    ControlError.MALFORMED_REQUEST,
                    Map.of("reason", malformed.getMessage()),
                    malformed);
        }
    }
}
