package io.tapstate.tools.catalog.assembler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import io.tapstate.core.catalog.CatalogEntryAssembler;
import io.tapstate.core.catalog.ConnectorOverlay;
import io.tapstate.core.catalog.CatalogJson;
import io.tapstate.core.catalog.ConnectorCatalogEntry;
import io.tapstate.core.catalog.NormalizedSpec;
import io.tapstate.core.catalog.SpecNormalizer;

/**
 * Drives the catalog assembly: for each walked connector it parses the spec (reusing core-catalog's
 * JSON reader), normalizes it, merges the derived capability bitmap and any declared modes via the
 * core merge rules, and stamps provenance. Alongside the entries it builds the ingest report,
 * surfacing every degradation — unclassified connectors, modes nobody declared and only derivation
 * stands behind, sinks defaulted with no DML signal, unrecognized type tokens and unresolved label
 * refs — so nothing is lost silently. Pure: file reads are the caller's, supplied as {@code specContent}.
 */
final class CatalogAssembler {

    /**
     * The only two modes capabilities can speak to, and which capability each needs. Deliberately just
     * these two: stream, api and file are underivable by construction, so checking them would flag
     * every connector we declare — which is all eighteen of them — and a report that always lists the
     * same names is one nobody reads. What this catches is a mode we claimed that the connector's own
     * capabilities contradict, which no other gate sees: an entry that exists is trusted, so a wrong
     * cdc here is admitted by validation rather than refused.
     */
    private static final Map<String, String> DERIVABLE_FROM =
            Map.of("snapshot", "batch_read_function", "cdc", "stream_read_function");

    private static final String WRITE_RECORD = "write_record_function";

    private CatalogAssembler() {
    }

    static Assembly assemble(WalkResult walk, String connectorRepoSha,
                             Map<String, Set<String>> bitmap,
                             ConnectorOverlay overlay,
                             Function<String, String> specContent) {
        List<ConnectorCatalogEntry> entries = new ArrayList<>();
        List<String> ingestedIds = new ArrayList<>();
        List<String> unclassified = new ArrayList<>();
        List<String> notDerived = new ArrayList<>();
        List<String> unverifiedModes = new ArrayList<>();
        List<String> overlayDivergences = new ArrayList<>();
        List<String> overlayNotDerivable = new ArrayList<>();
        List<String> sinkDefaultedNoSignal = new ArrayList<>();
        List<String> unknownTypeFields = new ArrayList<>();
        List<String> unresolvedLabelRefs = new ArrayList<>();

        List<ConnectorSource> ordered = new ArrayList<>(walk.sources());
        ordered.sort(java.util.Comparator.comparing(ConnectorSource::id));
        for (ConnectorSource source : ordered) {
            String content = specContent.apply(source.specPath());
            Map<String, Object> tree = asMap(CatalogJson.parse(content));
            NormalizedSpec spec = SpecNormalizer.normalize(tree);
            Set<String> caps = bitmap.getOrDefault(source.id(), Set.of());
            String hash = sha256(content);

            ConnectorCatalogEntry entry =
                    CatalogEntryAssembler.assemble(spec, caps, overlay, connectorRepoSha,
                            source.specPath(), hash);
            entries.add(entry);
            ingestedIds.add(entry.id());

            // A Java connector (has a class) absent from the bitmap was not derived this refresh — its
            // jar was not built, or it would not classload (a platform-excluded build). That is a
            // distinct gap from a connector that was probed (or is JavaScript) and still resolved no
            // mode, so keep the two apart rather than lumping a build gap into unclassified.
            boolean notDerivedThisRun = source.connectorClassFqn() != null && !bitmap.containsKey(source.id());
            if (notDerivedThisRun) {
                notDerived.add(entry.id());
            } else if (entry.modes().isEmpty()) {
                unclassified.add(entry.id());
            }
            if (entry.modesAreUnverified()) {
                unverifiedModes.add(entry.id());
            }
            List<String> ourModes = overlay.modesFor(source.id());
            if (ourModes != null) {
                if (spec.declaredModes() != null && !spec.declaredModes().equals(ourModes)) {
                    // Only when they actually differ. Reporting agreement too would print every
                    // connector we declare, on every run, and bury the one line that matters.
                    overlayDivergences.add(entry.id()
                            + ": upstream " + spec.declaredModes() + ", ours " + ourModes);
                }
                for (String mode : ourModes) {
                    String needed = DERIVABLE_FROM.get(mode);
                    if (needed != null && !caps.contains(needed)) {
                        overlayNotDerivable.add(entry.id() + ": " + mode + " needs " + needed);
                    }
                }
            }
            if (sinkDefaultedNoSignal(spec, caps)) {
                sinkDefaultedNoSignal.add(entry.id());
            }
            SpecAuditor.Findings findings = SpecAuditor.audit(tree);
            findings.unknownTypeFields().forEach(f -> unknownTypeFields.add(entry.id() + ":" + f));
            findings.unresolvedLabelRefs().forEach(k -> unresolvedLabelRefs.add(entry.id() + ":" + k));
        }

        IngestReport report = new IngestReport(connectorRepoSha, ingestedIds, unclassified, notDerived,
                unverifiedModes, overlayDivergences, overlayNotDerivable, sinkDefaultedNoSignal,
                unknownTypeFields, unresolvedLabelRefs, walk.exemptions());
        return new Assembly(entries, report);
    }

    /** Write-capable but the spec carries no DML policy, so the write semantics were a defaulted
     *  superset with no signal — indistinguishable from a real one without this flag. */
    private static boolean sinkDefaultedNoSignal(NormalizedSpec spec, Set<String> caps) {
        return caps.contains(WRITE_RECORD)
                && spec.dmlInsertAlternatives().isEmpty()
                && !spec.hasDmlUpdatePolicy();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        return value instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    /** First 16 hex chars of the spec's SHA-256 — enough to detect any upstream content change. */
    private static String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) {
                hex.append(String.format("%02x", digest[i]));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
