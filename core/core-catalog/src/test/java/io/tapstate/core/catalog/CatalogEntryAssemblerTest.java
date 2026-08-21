package io.tapstate.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.tapstate.core.model.SourceMode;
import io.tapstate.core.model.WriteMode;

class CatalogEntryAssemblerTest {

    private static final Set<String> DB_CAPS =
            Set.of("batch_read_function", "stream_read_function", "write_record_function");

    @Test
    void databaseConnectorDerivesCdcSnapshotAndAnUpsertSink() {
        NormalizedSpec spec = spec("mysql", ConnectorGroup.DATABASE,
                List.of("update_on_exists", "ignore_on_exists", "just_insert"), true, null);

        ConnectorCatalogEntry entry =
                CatalogEntryAssembler.assemble(spec, DB_CAPS, noOverlay(), "20371556", "mysql/mysql-spec.json", "h1");

        assertThat(entry.modes()).containsExactly(SourceMode.CDC, SourceMode.SNAPSHOT);
        assertThat(entry.group()).isEqualTo(ConnectorGroup.DATABASE);
        assertThat(entry.discovery()).isEqualTo(Discovery.CATALOG);
        assertThat(entry.sink().capable()).isTrue();
        assertThat(entry.sink().writeSemantics()).containsExactly(WriteMode.UPSERT, WriteMode.APPEND);
        assertThat(entry.pushOut()).isFalse();
        assertThat(entry.provenance().modeSource())
                .containsEntry(SourceMode.CDC, ModeSource.DERIVED)
                .containsEntry(SourceMode.SNAPSHOT, ModeSource.DERIVED);
    }

    @Test
    void declaredStreamModeReplacesTheDerivedDefaultAndClassifiesAsMq() {
        // Kafka mis-tags itself Database and registers stream read; the declared modes win, and the
        // stream mode plus the name push it to the MQ group with push-out enabled.
        NormalizedSpec spec = spec("kafka", ConnectorGroup.DATABASE, List.of(), false, List.of("stream"));

        ConnectorCatalogEntry entry = CatalogEntryAssembler.assemble(
                spec, Set.of("stream_read_function", "write_record_function"), noOverlay(), "20371556",
                "kafka.json", "h2");

        assertThat(entry.modes()).containsExactly(SourceMode.STREAM);
        assertThat(entry.group()).isEqualTo(ConnectorGroup.MQ);
        assertThat(entry.pushOut()).isTrue();
        assertThat(entry.sink().capable()).isTrue();
        assertThat(entry.provenance().modeSource()).containsEntry(SourceMode.STREAM, ModeSource.DECLARED);
    }

    @Test
    void fileConnectorHasNoCatalogAndNoSinkWhenWriteIsAbsent() {
        NormalizedSpec spec = spec("csv", ConnectorGroup.FILE, List.of(), false, List.of("file"));

        ConnectorCatalogEntry entry = CatalogEntryAssembler.assemble(
                spec, Set.of("batch_read_function"), noOverlay(), "20371556", "csv.json", "h3");

        assertThat(entry.modes()).containsExactly(SourceMode.FILE);
        assertThat(entry.group()).isEqualTo(ConnectorGroup.FILE);
        assertThat(entry.discovery()).isEqualTo(Discovery.NONE);
        assertThat(entry.sink().capable()).isFalse();
        assertThat(entry.sink().writeSemantics()).isEmpty();
    }

    @Test
    void apiConnectorClassifiesAsSaas() {
        NormalizedSpec spec = spec("github", ConnectorGroup.OTHER, List.of(), false, List.of("api"));

        ConnectorCatalogEntry entry =
                CatalogEntryAssembler.assemble(spec, Set.of(), noOverlay(), "20371556", "github.json", "h4");

        assertThat(entry.modes()).containsExactly(SourceMode.API);
        assertThat(entry.group()).isEqualTo(ConnectorGroup.SAAS);
        assertThat(entry.sink().capable()).isFalse();
    }

    @Test
    void carriesIdentityConfigAndProvenanceStamp() {
        ConfigField host = new ConfigField("host", ConfigType.STRING, java.util.Map.of("en_US", "Host"),
                true, null, false, List.of(), null);
        NormalizedSpec spec = new NormalizedSpec("mysql", "Mysql", "MySQL", "icons/mysql.png",
                ConnectorGroup.DATABASE, List.of(host), List.of(), false, null);

        ConnectorCatalogEntry entry =
                CatalogEntryAssembler.assemble(spec, DB_CAPS, noOverlay(), "20371556", "mysql/mysql-spec.json", "hash-abc");

        assertThat(entry.id()).isEqualTo("mysql");
        assertThat(entry.displayName()).isEqualTo("MySQL");
        assertThat(entry.icon()).isEqualTo("icons/mysql.png");
        assertThat(entry.config()).extracting(ConfigField::name).containsExactly("host");
        assertThat(entry.provenance().connectorRepoSha()).isEqualTo("20371556");
        assertThat(entry.provenance().specPath()).isEqualTo("mysql/mysql-spec.json");
        assertThat(entry.provenance().specContentHash()).isEqualTo("hash-abc");
    }

    @Test
    void rejectsAnUnknownDeclaredMode() {
        NormalizedSpec spec = spec("weird", ConnectorGroup.OTHER, List.of(), false, List.of("teleport"));

        assertThatThrownBy(() -> CatalogEntryAssembler.assemble(spec, Set.of(), noOverlay(), "sha", "p", "h"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static NormalizedSpec spec(String id, ConnectorGroup tagGroup, List<String> dmlInsert,
                                       boolean hasUpdate, List<String> declaredModes) {
        return new NormalizedSpec(id, id, id, null, tagGroup, List.of(), dmlInsert, hasUpdate, declaredModes);
    }

    private static ConnectorOverlay noOverlay() {
        return ConnectorOverlay.read(java.util.Map.of("/catalog/overlay/pdk/index.json", "[]")::get);
    }

    private static ConnectorOverlay overlayDeclaring(String id, String... modes) {
        String quoted = java.util.Arrays.stream(modes).map(m -> '"' + m + '"')
                .collect(java.util.stream.Collectors.joining(","));
        return ConnectorOverlay.read(java.util.Map.of(
                "/catalog/overlay/pdk/index.json", "[\"" + id + "\"]",
                "/catalog/overlay/pdk/" + id + ".json", "{\"modes\":[" + quoted + "]}")::get);
    }

    @Test
    void ourOverlayOutranksTheConnectorsOwnDeclaration() {
        // Both sources speak and they disagree. The overlay is the reviewed one, so it wins outright -
        // and the row says so, which is what lets the ingest report tell a real divergence from the
        // ordinary case of the two agreeing.
        NormalizedSpec spec = spec("kafka", ConnectorGroup.DATABASE, List.of(), false, List.of("cdc"));

        ConnectorCatalogEntry entry = CatalogEntryAssembler.assemble(
                spec, Set.of("stream_read_function"), overlayDeclaring("kafka", "stream"), "sha",
                "kafka.json", "h");

        assertThat(entry.modes()).containsExactly(SourceMode.STREAM);
        assertThat(entry.provenance().modeSource())
                .containsExactly(java.util.Map.entry(SourceMode.STREAM, ModeSource.OVERLAY));
    }

    @Test
    void ourOverlayOutranksDerivedDefaults() {
        NormalizedSpec spec = spec("rabbitmq", ConnectorGroup.DATABASE, List.of(), false, null);

        ConnectorCatalogEntry entry = CatalogEntryAssembler.assemble(
                spec, Set.of("stream_read_function"), overlayDeclaring("rabbitmq", "stream"), "sha",
                "rabbitmq.json", "h");

        // Without the overlay this would derive cdc, and connector: rabbitmq with mode: cdc would pass
        // validation - the precise mis-derivation the declaration exists to overrule.
        assertThat(entry.modes()).containsExactly(SourceMode.STREAM);
    }

    @Test
    void aConnectorTheOverlayIsSilentAboutIsUntouched() {
        NormalizedSpec spec = spec("mysql", ConnectorGroup.DATABASE, List.of(), false, null);

        ConnectorCatalogEntry entry = CatalogEntryAssembler.assemble(
                spec, DB_CAPS, overlayDeclaring("kafka", "stream"), "sha", "mysql.json", "h");

        assertThat(entry.provenance().modeSource().values()).containsOnly(ModeSource.DERIVED);
    }
}
