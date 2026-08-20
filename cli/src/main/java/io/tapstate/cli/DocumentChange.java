package io.tapstate.cli;

import java.util.List;

/**
 * One field-level difference between two versions of the same row.
 *
 * <p>{@code before} and {@code after} are the field's two values, either of which is null when the
 * field was not there on that side. {@code addedEntries} is non-empty only for a list that grew: it
 * holds the entries it gained, so a view can show what arrived instead of reprinting the whole list —
 * which for a row whose interesting field is a growing array is the difference between a line and a
 * screen.
 *
 * @param mark how the field differs, and the character a view puts in its left slot. The mark is a
 *             value rather than a character so the two views cannot drift into different symbols for
 *             the same difference; each renders it itself.
 */
record DocumentChange(String field, Mark mark, Object before, Object after, List<Object> addedEntries) {

    /** How a field differs between two versions of a row. */
    enum Mark {

        /** The field arrived, or a list under it gained entries. */
        ADDED('+'),

        /** The field was there before and says something else now. */
        CHANGED('~'),

        /** The field is no longer there. */
        REMOVED('-');

        private final char slot;

        Mark(char slot) {
            this.slot = slot;
        }

        /**
         * The character a view puts in the slot beside the field. Colour is only ever laid over this,
         * never instead of it: a terminal without colour, a pasted transcript and a reader who cannot
         * see the hue all keep the mark.
         */
        char slot() {
            return slot;
        }
    }

    DocumentChange {
        addedEntries = addedEntries == null ? List.of() : List.copyOf(addedEntries);
    }

    /** A field-level change with nothing added, the shape of every difference but a grown list. */
    static DocumentChange of(String field, Mark mark, Object before, Object after) {
        return new DocumentChange(field, mark, before, after, List.of());
    }
}
