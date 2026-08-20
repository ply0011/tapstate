package io.tapstate.core.catalog;

import java.util.List;

/**
 * The connectors this release officially supports — the one place that set is written down.
 *
 * <p>It lives here, beside the catalog, because two unrelated surfaces have to agree on it: the
 * runtime register path refuses anything outside it, and the authoring surfaces offer only what that
 * path would accept. Holding a second copy on either side is how the two drift, and the drift is
 * invisible until a user is offered a connector that cannot be installed.
 *
 * <p>A list rather than a set, so the order — and therefore the order a refusal message names them
 * in — is fixed. The membership test over a handful of entries costs nothing.
 *
 * <p>Being outside this set is a support boundary, not a judgement about the connector: the set grows
 * as connectors are certified, and a connector absent from it may well be perfectly functional.
 */
public final class OfficialConnectors {

    /** The supported ids, in the order a message naming them should read. */
    public static final List<String> IDS = List.of("mysql", "mongodb");

    private OfficialConnectors() {
    }

    /** Whether {@code connectorId} is one this release supports. */
    public static boolean isOfficial(String connectorId) {
        return IDS.contains(connectorId);
    }
}
