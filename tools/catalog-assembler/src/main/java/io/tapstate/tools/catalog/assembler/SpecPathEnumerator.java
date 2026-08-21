package io.tapstate.tools.catalog.assembler;

import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;

import io.tapstate.core.catalog.ConnectorCatalogEntry;

/** Decides which upstream specification files a drift scan has to read. */
final class SpecPathEnumerator {

    /**
     * A module's own resource directory, one level under some container: {@code <tree>/<module>/
     * src/main/resources/<file>.json}. Named by shape rather than by tree, so a container nobody
     * here has heard of is still walked. Files nested deeper under {@code resources/} are left out —
     * a specification sits at the top of that directory, and the subdirectories hold documentation
     * and icons.
     */
    private static final Pattern MODULE_RESOURCE_JSON =
            Pattern.compile("[^/]+/[^/]+/src/main/resources/[^/]+\\.json");

    private SpecPathEnumerator() {
    }

    /**
     * The specification files the checked-in snapshot claims, each row naming its own. Driven by what
     * the rows record rather than by a directory this side names: connectors live in more than one
     * tree, the trees are the upstream's to add to, and a scan that walks named directories reports
     * "nothing to read" for a tree it was never told about — indistinguishable from a tree that did
     * not drift.
     *
     * <p>Rows carrying no path are skipped rather than refused: a row registered at runtime has no
     * upstream file behind it, and there is nothing to fetch for it.
     */
    static List<String> declaredSpecPaths(List<ConnectorCatalogEntry> snapshot) {
        TreeSet<String> paths = new TreeSet<>();
        for (ConnectorCatalogEntry entry : snapshot) {
            String path = entry.provenance() == null ? null : entry.provenance().specPath();
            if (path != null && !path.isBlank()) {
                paths.add(path);
            }
        }
        return List.copyOf(paths);
    }

    /**
     * Everything one scan has to pull down: the file behind every checked-in row, plus every upstream
     * file shaped like a module's specification. The second half is what finds a connector that has no
     * row yet — the first half cannot, because a connector nobody has catalogued names no path here.
     *
     * <p>The shape filter is deliberately loose. What makes a file a connector specification is the id
     * inside it, which costs a read; filtering on the name instead would be a guess about how the
     * upstream names its files, and a guess that excludes too much goes unnoticed — the connector it
     * dropped simply never appears, which reads exactly like an upstream that added nothing. So this
     * offers candidates and lets reading the id decide, paying a few extra kilobytes for it.
     */
    static List<String> specPathsToFetch(List<ConnectorCatalogEntry> snapshot, List<String> upstreamPaths) {
        TreeSet<String> paths = new TreeSet<>(declaredSpecPaths(snapshot));
        for (String path : upstreamPaths) {
            if (MODULE_RESOURCE_JSON.matcher(path).matches()) {
                paths.add(path);
            }
        }
        return List.copyOf(paths);
    }
}
