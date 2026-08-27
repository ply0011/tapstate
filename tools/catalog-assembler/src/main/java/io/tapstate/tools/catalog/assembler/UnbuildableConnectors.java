package io.tapstate.tools.catalog.assembler;

import java.util.Map;
import java.util.Set;

/**
 * Connectors this repository cannot build, and why — named one by one rather than discovered by a
 * build that fails.
 *
 * <p>A refresh builds every connector on the worklist from the upstream sources. Nine of them cannot
 * be built outside the upstream project's own network, for two unrelated reasons: three resolve a
 * database driver published only to that project's private repository and available from no public
 * one, and six do not compile at all, against classes their own declared dependencies do not carry.
 * Neither is something this repository can fix, and neither is a passing condition: the first is a
 * licensing boundary, the second is upstream's build graph.
 *
 * <p>Left to be discovered, one of them stops the whole reactor part-way through — so a refresh that
 * would have derived every other connector derives none, and the failure names a compiler or a
 * repository rather than the connector that is unbuildable. Named here, they are kept off the probe
 * worklist, which is what the build list is derived from, and they arrive in the ingest report with
 * the reason attached rather than in the bucket for connectors that fell out unexpectedly.
 *
 * <p>That distinction is the point of naming them. "We decided not to build this" and "this silently
 * stopped being built" are the same shape in a catalog otherwise, and only one of them is fine.
 * A connector that becomes buildable, or a new one that stops being so, has to change this list -
 * which is a reviewed edit, not a build that quietly got shorter.
 */
final class UnbuildableConnectors {

    private static final Map<String, String> REASONS = Map.of(
            "yashandb", "driver published only to the upstream project's private repository",
            "huawei-gauss-db", "driver published only to the upstream project's private repository",
            "highgo", "driver published only to the upstream project's private repository",
            "greenplum", "upstream module compiles against postgres-core, which nothing it depends on carries",
            "dws", "upstream module compiles against postgres-core, which nothing it depends on carries",
            "vastbase", "upstream module compiles against postgres-core, which nothing it depends on carries",
            "aws-clickhouse", "upstream module compiles against clickhouse classes its dependencies do not carry",
            "mongodb3", "upstream module does not compile against the current mongodb connector",
            "file-stream", "upstream module does not compile against the current file connector base");

    private UnbuildableConnectors() {
    }

    static boolean contains(String id) {
        return REASONS.containsKey(id);
    }

    static String reasonFor(String id) {
        return REASONS.get(id);
    }

    static Set<String> ids() {
        return REASONS.keySet();
    }
}
