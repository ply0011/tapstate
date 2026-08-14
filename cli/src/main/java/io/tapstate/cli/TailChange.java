package io.tapstate.cli;

import java.util.Map;

/**
 * One change the followed stream delivered: when it happened, which row it was, and the row as the
 * change carries it.
 *
 * <p>Two kinds, not the store's three. Whether a write created a row or altered one is a distinction
 * the reader of a change stream cannot act on and the stream itself does not reliably make — an
 * update to a row this view has never seen and an insert are the same event as far as anything on
 * screen can honestly say. A delete is different in kind: there is no row to show afterwards.
 *
 * @param row the row as the change carries it, which for an update may hold only the fields the
 *            write touched. Null for a delete.
 */
record TailChange(String at, String key, Map<String, Object> row, boolean deleted) {

    /** A row that now exists, or exists differently. */
    static TailChange upsert(String at, String key, Map<String, Object> row) {
        return new TailChange(at, key, row, false);
    }

    /** A row that is gone. */
    static TailChange delete(String at, String key) {
        return new TailChange(at, key, null, true);
    }
}
