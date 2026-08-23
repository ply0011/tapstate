package io.tapstate.tools.catalog.assembler;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders an {@link IngestReport} to deterministic markdown — fixed sections in a fixed order, items
 * sorted, and an explicit {@code (none)} for an empty section so it reads as "checked, nothing found"
 * rather than a gap. Checked in beside the catalog and byte-locked, so it must be stable.
 */
final class ReportRenderer {

    private ReportRenderer() {
    }

    static String render(IngestReport report) {
        StringBuilder head = new StringBuilder();
        head.append("# Connector catalog ingest report\n\n");
        // Two revisions rather than one, and both are printed even when equal: a reader comparing
        // them is how "the capability face is older than the spec face" becomes visible at all, and
        // printing one line only when they differ makes its absence carry meaning nobody can see.
        head.append("Spec SHA: `").append(report.specSha()).append("`\n");
        head.append("Capability SHA: `").append(report.capabilitySha()).append("`\n");
        // Both values are always printed, but two values a reader has to compare is not the same as
        // being told. A spec-only refresh advances one and leaves the other where the last derivation
        // put it, so every capability in this report was read from a different revision than the
        // structure beside it - the kind of thing that is obvious once said and invisible until then.
        if (!java.util.Objects.equals(report.specSha(), report.capabilitySha())) {
            head.append("\n> The capability face comes from an earlier upstream revision than the spec face: ")
                .append("modes, sink and write semantics were derived at `").append(report.capabilitySha())
                .append("`, while the structure below was read at `").append(report.specSha())
                .append("`. A full refresh brings them back together.\n");
        }
        head.append("Ingested connectors: ").append(report.ingestedIds().size()).append("\n\n");

        List<String> sections = new ArrayList<>();
        sections.add(section("Unclassified — no resolvable mode (need tapstate.modes)", report.unclassified()));
        sections.add(section("Not built — this repository cannot build these, by name and with reason",
                report.notBuilt()));
        sections.add(section("Not derived — no built jar or did not classload (excluded from refresh)",
                report.notDerived()));
        sections.add(section("Unverified modes — derived for a non-database connector nobody declared",
                report.unverifiedModes()));
        sections.add(section("Overlay divergences — our declaration differs from the connector's own",
                report.overlayDivergences()));
        sections.add(section("Overlay not derivable — we declare a mode the capabilities do not support",
                report.overlayNotDerivable()));
        sections.add(section("Sink semantics defaulted — no DML signal", report.sinkDefaultedNoSignal()));
        sections.add(section("Unrecognized type tokens — fell to string input", report.unknownTypeFields()));
        sections.add(section("Unresolved label refs — fell back to raw key", report.unresolvedLabelRefs()));
        sections.add(section("Exemptions — modules and specs set aside", exemptionLines(report.exemptions())));

        return head + String.join("\n", sections);
    }

    private static String section(String title, List<String> items) {
        StringBuilder sb = new StringBuilder("## ").append(title).append('\n');
        if (items.isEmpty()) {
            sb.append("(none)\n");
        } else {
            items.stream().sorted().forEach(item -> sb.append("- ").append(item).append('\n'));
        }
        return sb.toString();
    }

    private static List<String> exemptionLines(List<Exemption> exemptions) {
        List<String> lines = new ArrayList<>();
        for (Exemption exemption : exemptions) {
            lines.add("[" + exemption.category() + "] " + exemption.module() + ": " + exemption.detail());
        }
        return lines;
    }
}
