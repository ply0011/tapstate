package io.tapstate.control.restapi;

import java.util.Map;

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
record DataBrowserFindRequest(Map<String, Object> filter, Sort sort, Integer limit) {

    /**
     * The order asked for: one field and a direction, spelled {@code asc} or {@code desc}. Held as text
     * rather than bound to an enum so an unusable direction is refused at this boundary with a reason the
     * caller can read, rather than as a decode failure naming a type they cannot see.
     */
    record Sort(String field, String dir) {
    }
}
