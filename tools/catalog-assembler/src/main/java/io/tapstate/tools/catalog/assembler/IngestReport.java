package io.tapstate.tools.catalog.assembler;

import java.util.List;

/**
 * The accounting of one catalog assembly run: what was ingested and every degradation that must be
 * visible rather than silent — connectors with no resolvable mode (unclassified), Java connectors
 * whose capabilities could not be derived this refresh because their jar was not built or would not
 * classload (not derived) as against the ones this repository is known not to build at all,
 * each with its reason (not built), non-database connectors whose modes came only from derivation and so
 * rest on a guess nobody checked (unverified modes), connectors where our overlay and the
 * connector's own upstream
 * declaration disagree (overlay divergences — silence here means they agree, which is the ordinary
 * case and is deliberately not listed), overlay declarations of a derivable mode that the capability
 * bitmap does not support (overlay not derivable), sinks whose write semantics were defaulted with no
 * DML signal,
 * unrecognized Formily type tokens and unresolved i18n label refs, plus the modules the walk set
 * aside. Checked in beside the catalog so every gap is reviewable in the PR.
 */
record IngestReport(String specSha,
                    String capabilitySha,
                    List<String> ingestedIds,
                    List<String> unclassified,
                    List<String> notDerived,
                    List<String> notBuilt,
                    List<String> unverifiedModes,
                    List<String> overlayAlone,
                    List<String> overlayDivergences,
                    List<String> overlayNotDerivable,
                    List<String> sinkDefaultedNoSignal,
                    List<String> unknownTypeFields,
                    List<String> unresolvedLabelRefs,
                    List<Exemption> exemptions) {
}
