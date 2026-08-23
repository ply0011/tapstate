package io.tapstate.tools.catalog.assembler;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The byte-lock this decorates only runs where a connectors checkout exists, so it is skipped in a
 * normal build. These run always - otherwise the reporting code would be exercised for the first
 * time by the failure it exists to explain.
 */
class CatalogEntryDriftTest {

    private static final String ENTRY = """
            {"id":"kafka","modes":["stream"],"sink":{"capable":true},\
            "provenance":{"specContentHash":"d889798e","modeSource":{"stream":"overlay"}}}""";

    @Test
    @DisplayName("identical entries produce no clause at all")
    void identicalEntriesProduceNoClause() {
        assertThat(CatalogEntryDrift.describe(ENTRY, ENTRY)).isEmpty();
    }

    @Test
    @DisplayName("a changed mode is named with both values")
    void aChangedModeIsNamedWithBothValues() {
        String regenerated = ENTRY.replace("[\"stream\"]", "[\"cdc\"]");
        assertThat(CatalogEntryDrift.describe(ENTRY, regenerated))
                .contains("modes (checked-in [stream], regenerated [cdc])")
                .doesNotContain("provenance");
    }

    @Test
    @DisplayName("a changed provenance field is named one level in, not as the whole object")
    void aChangedProvenanceFieldIsNamedOneLevelIn() {
        // Reporting "provenance" as changed would be true and useless: every re-pin changes it. The
        // question a reader has is which of the spec hash and the mode attribution moved, because one
        // is routine and the other is a capability claim.
        String regenerated = ENTRY.replace("d889798e", "aaaa1111");
        assertThat(CatalogEntryDrift.describe(ENTRY, regenerated))
                .contains("provenance.specContentHash (checked-in d889798e, regenerated aaaa1111)")
                .doesNotContain("provenance.modeSource");
    }

    @Test
    @DisplayName("an attribution that flips from our declaration to a guess is named")
    void anAttributionThatFlipsToAGuessIsNamed() {
        String regenerated = ENTRY.replace("\"overlay\"", "\"derived\"");
        assertThat(CatalogEntryDrift.describe(ENTRY, regenerated))
                .contains("provenance.modeSource")
                .contains("overlay")
                .contains("derived");
    }

    @Test
    @DisplayName("a field present on only one side is named as absent, not silently skipped")
    void aFieldPresentOnOnlyOneSideIsNamedAsAbsent() {
        String regenerated = ENTRY.replace(",\"sink\":{\"capable\":true}", "");
        String described = CatalogEntryDrift.describe(ENTRY, regenerated);
        assertThat(described).contains("sink").contains("absent");
    }

    @Test
    @DisplayName("unparseable content says so instead of throwing from the failure path")
    void unparseableContentSaysSoInsteadOfThrowing() {
        // What a truncated or half-written artifact looks like. The reporter runs while a gate is
        // already failing; throwing here would replace the drift report with its own stack trace.
        assertThat(CatalogEntryDrift.describe(ENTRY, "{\"id\":\"kafka\","))
                .contains("not a JSON object")
                .contains("compare the files directly");
    }

    @Test
    @DisplayName("content-equal but byte-unequal says that, rather than naming no fields")
    void contentEqualButByteUnequalSaysSo() {
        // The byte-lock is stricter than field equality on purpose. When it fires for formatting
        // alone, "fields that differ: none" would read as a bug in the reporter.
        String reformatted = ENTRY.replace("{\"id\"", "{ \"id\"");
        assertThat(CatalogEntryDrift.describe(ENTRY, reformatted))
                .contains("agree in content but not byte for byte");
    }
}
