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

    /**
     * The supported ids, in the order a message naming them should read.
     *
     * <p>Three engines, each with its managed variants. The variants are enumerated one by one rather
     * than matched by prefix, because nothing in a connector's identity says which engine it belongs
     * to: membership is a decision somebody made about a named connector, and a prefix rule would
     * silently admit every future product whose id happens to begin the same way. Being listed here
     * means the register path accepts the connector, which is not the same as the release having
     * verified it — accepting a variant rests on it being the same engine underneath, and only the
     * three engines themselves are exercised.
     */
    public static final List<String> IDS = List.of(
            "mysql", "aliyun-rds-mysql", "aws-rds-mysql", "polar-db-mysql", "mysql-pxc",
            "postgres", "aliyun-rds-postgres", "aliyun-adb-postgres", "polar-db-postgres",
            "tencent-db-postgres",
            "mongodb", "mongodb-atlas", "mongodb3", "aliyun-db-mongodb", "tencent-db-mongodb");

    private OfficialConnectors() {
    }

    /** Whether {@code connectorId} is one this release supports. */
    public static boolean isOfficial(String connectorId) {
        return IDS.contains(connectorId);
    }

    /**
     * The supported ids {@code catalog} actually carries, in this list's order — what an authoring
     * surface may offer, and what a refusal names. Intersected rather than returned outright so that
     * everything offered also resolves: a supported id absent from the catalog has no fields to
     * prompt for. Kept in this list's order rather than the catalog's so a menu and the message that
     * follows a refusal read the same way round.
     */
    public static List<String> presentIn(TapstateCatalog catalog) {
        List<String> present = catalog.ids();
        return IDS.stream().filter(present::contains).toList();
    }
}
