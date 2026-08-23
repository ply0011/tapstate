package io.tapstate.tools.catalog.assembler;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The ingest report is a checked-in, byte-locked audit artifact, so its rendering must be
 * deterministic: fixed sections in a fixed order, items sorted, and an explicit "(none)" where a
 * degradation did not occur — so an empty section reads as "checked, nothing found", never as a gap.
 */
class ReportRendererTest {

    @Test
    void rendersEverySectionDeterministicallyWithNoneWhereEmpty() {
        IngestReport report = new IngestReport(
                "20371556abc",
                "9f0c11abdef",
                List.of("github", "kafka", "mysql"),
                List.of("github"),
                List.of("hazelcast"),
                List.of("yashandb: driver published only to the upstream project's private repository"),
                List.of("kafka"),
                List.of("rabbitmq: upstream [cdc], ours [stream]"),
                List.of("selectdb: snapshot needs batch_read_function"),
                List.of("kafka"),
                List.of(),
                List.of(),
                List.of(new Exemption(Exemption.Category.EXCLUDED, "tdd-connector", "known non-connector module")));

        assertThat(ReportRenderer.render(report)).isEqualTo("""
                # Connector catalog ingest report

                Spec SHA: `20371556abc`
                Capability SHA: `9f0c11abdef`

                > The capability face comes from an earlier upstream revision than the spec face: modes, sink and write semantics were derived at `9f0c11abdef`, while the structure below was read at `20371556abc`. A full refresh brings them back together.
                Ingested connectors: 3

                ## Unclassified — no resolvable mode (need tapstate.modes)
                - github

                ## Not built — this repository cannot build these, by name and with reason
                - yashandb: driver published only to the upstream project's private repository

                ## Not derived — no built jar or did not classload (excluded from refresh)
                - hazelcast

                ## Unverified modes — derived for a non-database connector nobody declared
                - kafka

                ## Overlay divergences — our declaration differs from the connector's own
                - rabbitmq: upstream [cdc], ours [stream]

                ## Overlay not derivable — we declare a mode the capabilities do not support
                - selectdb: snapshot needs batch_read_function

                ## Sink semantics defaulted — no DML signal
                - kafka

                ## Unrecognized type tokens — fell to string input
                (none)

                ## Unresolved label refs — fell back to raw key
                (none)

                ## Exemptions — modules and specs set aside
                - [EXCLUDED] tdd-connector: known non-connector module
                """);
    }

    @Test
    void saysNothingAboutRevisionsWhenTheTwoFacesAgree() {
        // The note has to be absent after a full refresh, not merely quieter. A report that carried it
        // every time would train a reader to skip the line that only sometimes means something - and
        // that line is the only warning that every capability below was read at another revision.
        IngestReport report = new IngestReport("samesha", "samesha", List.of("mysql"),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of());

        assertThat(ReportRenderer.render(report))
                .contains("Spec SHA: `samesha`")
                .contains("Capability SHA: `samesha`")
                .doesNotContain("earlier upstream revision");
    }
}
