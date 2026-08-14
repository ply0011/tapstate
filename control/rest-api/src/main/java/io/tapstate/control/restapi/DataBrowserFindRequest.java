package io.tapstate.control.restapi;

import java.util.List;

/**
 * The body of a data-browser read: which rows to match, in what order, and how many at most. Which
 * collection is read comes from the path, so this record carries exactly the request shape the read face
 * defines and every surface sends — filter, order, size.
 *
 * <p>All three are optional, and an absent one is a request rather than a gap: no filter reads every row,
 * no order leaves the order to the database, and no size takes the control plane's default. What the
 * shape has no room for is as load-bearing as what it has: a read is one-shot, so there is no
 * continuation field here, and the surface is configured to refuse a body carrying one instead of
 * serving an answer that quietly ignored it.
 */
record DataBrowserFindRequest(Filter filter, Sort sort, Integer limit) {

    /**
     * The order asked for: one field and a direction, spelled {@code asc} or {@code desc}. Held as text
     * rather than bound to an enum so an unusable direction is refused at this boundary with a reason the
     * caller can read, rather than as a decode failure naming a type they cannot see.
     */
    record Sort(String field, String dir) {
    }

    /**
     * Which rows to match, in Tapstate's own vocabulary rather than the store's: either one term
     * ({@code field} / {@code op} / {@code value}) or one combination of terms ({@code all} or
     * {@code any}). Both spellings live in one record because that is what the two shapes are over JSON —
     * one object, whose keys say which of the two it is — and reading them apart here lets a body that is
     * neither be refused with a reason instead of binding into something half-formed.
     *
     * <p>{@code op} is held as text for the same reason {@code dir} above is: an operator outside the
     * vocabulary is client input, and the useful answer names the word that was not understood rather
     * than reporting a decode failure against a type the caller cannot see. That matters most for exactly
     * the request this shape exists to stop — a backend operator sent as if it were one of ours.
     *
     * <p>A combination holds terms rather than further combinations, so the one-level bound the
     * vocabulary is built on is checked here, at the only boundary where a deeper one can be written
     * down at all.
     */
    record Filter(String field, String op, Object value, List<Filter> all, List<Filter> any) {
    }
}
