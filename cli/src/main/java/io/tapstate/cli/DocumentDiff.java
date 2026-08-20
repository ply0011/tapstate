package io.tapstate.cli;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * What changed between two versions of one row. Both live views render from this same value: one
 * redraws the row with a mark beside each changed field, the other appends a line naming the same
 * changes. Comparing twice — once per view — is how the two would come to disagree about what changed
 * while both looked right on their own.
 *
 * <p>Fields are compared at the top level only. A row here is a preview of a document, and a reader
 * watching one is watching its fields; walking into nested objects would turn one line of "this
 * changed" into a tree of paths, which is a different tool. A list is the one exception, and only for
 * the question a growing array actually raises: did it gain entries, and which.
 *
 * <p>Field order is the row's own, so a redraw never reshuffles what the reader is looking at.
 */
final class DocumentDiff {

    private final List<String> fields;
    private final List<DocumentChange> changes;

    private DocumentDiff(List<String> fields, List<DocumentChange> changes) {
        this.fields = List.copyOf(fields);
        this.changes = List.copyOf(changes);
    }

    /**
     * Compares two versions of a row. A null side is the row not existing then: null before is a first
     * sighting — every field arrives at once rather than being reported as an edit of nothing — and
     * null after is the row going away.
     */
    static DocumentDiff between(Map<String, Object> before, Map<String, Object> after) {
        Map<String, Object> old = before == null ? Map.of() : before;
        Map<String, Object> current = after == null ? Map.of() : after;
        List<DocumentChange> changes = new ArrayList<>();
        // The old row's order first, then whatever the new one added, so the reader's existing view of
        // the row keeps its shape and new fields land at the end.
        Set<String> fields = new LinkedHashSet<>(old.keySet());
        fields.addAll(current.keySet());
        for (String field : fields) {
            if (!current.containsKey(field)) {
                changes.add(DocumentChange.of(field, DocumentChange.Mark.REMOVED, old.get(field), null));
                continue;
            }
            if (!old.containsKey(field)) {
                changes.add(DocumentChange.of(field, DocumentChange.Mark.ADDED, null, current.get(field)));
                continue;
            }
            Object was = old.get(field);
            Object is = current.get(field);
            if (Objects.equals(was, is)) {
                continue;
            }
            List<Object> gained = entriesGained(was, is);
            changes.add(gained.isEmpty()
                    ? DocumentChange.of(field, DocumentChange.Mark.CHANGED, was, is)
                    : new DocumentChange(field, DocumentChange.Mark.ADDED, was, is, gained));
        }
        return new DocumentDiff(new ArrayList<>(fields), changes);
    }

    /**
     * The entries a list field gained, or empty when it did not gain any. Growth is only claimed when
     * everything that was there is still there, in place: a list that was rewritten happens to be
     * longer is a change to the whole field, and calling it an addition would show the reader entries
     * that arrived beside others that quietly went away.
     */
    private static List<Object> entriesGained(Object before, Object after) {
        if (!(before instanceof List<?> was) || !(after instanceof List<?> is) || is.size() <= was.size()) {
            return List.of();
        }
        if (!is.subList(0, was.size()).equals(was)) {
            return List.of();
        }
        return List.copyOf(is.subList(was.size(), is.size()));
    }

    /** Every field-level difference, in the row's own field order. */
    List<DocumentChange> changes() {
        return changes;
    }

    /**
     * Every field either version holds, in the order a view renders them: the old row's own order,
     * then whatever the new one added. A field that went away keeps its place rather than being
     * appended at the end — it is still where the reader last saw it, which is where they will look
     * for it.
     */
    List<String> fields() {
        return fields;
    }

    /** The difference reported for {@code field}, or null when it did not change. */
    DocumentChange change(String field) {
        for (DocumentChange change : changes) {
            if (change.field().equals(field)) {
                return change;
            }
        }
        return null;
    }

    /**
     * Whether the two versions differ in nothing. This is the answer both views branch on, and it has
     * to be reachable without walking the changes: a frame with nothing in it must not be redrawn, and
     * a stream must not emit a line for it.
     */
    boolean isEmpty() {
        return changes.isEmpty();
    }

    /**
     * One line naming what changed, for a footer or a stream line. A grown list reads as the count it
     * gained ({@code +1 shipments}) because that is the fact a reader wants at a glance; every other
     * difference is its mark and its field.
     */
    String summary() {
        StringBuilder summary = new StringBuilder();
        for (DocumentChange change : changes) {
            if (!summary.isEmpty()) {
                summary.append(", ");
            }
            summary.append(change.mark().slot());
            if (!change.addedEntries().isEmpty()) {
                summary.append(change.addedEntries().size()).append(' ');
            }
            summary.append(change.field());
        }
        return summary.toString();
    }
}
