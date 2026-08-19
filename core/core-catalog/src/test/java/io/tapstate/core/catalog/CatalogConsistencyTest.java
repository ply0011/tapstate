package io.tapstate.core.catalog;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.tapstate.core.model.SourceMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the real bundled catalog the build tool generated into main resources — the artifact the
 * runtime ships. This runs in every build (no connectors checkout, no PDK), so it is the always-on
 * guard that the checked-in catalog is internally consistent: the index and the per-connector entries
 * agree, every entry reconstructs, and the well-known connectors are present. A broken or empty
 * regeneration fails here. Assertions are by floor and by known id, not by exact count, so adding a
 * connector does not break this test.
 */
class CatalogConsistencyTest {

    private final TapstateCatalog catalog = TapstateCatalog.load();

    @Test
    void loadsAndKeepsTheIndexAndEntriesInStep() {
        // load() rejects a duplicate index id; this confirms every index id has exactly one entry.
        assertThat(catalog.ids()).doesNotHaveDuplicates();
        assertThat(catalog.all()).hasSameSizeAs(catalog.ids());
    }

    @Test
    void carriesTheWholeOfficialConnectorSet() {
        // A floor with headroom, not an exact count, so adding/removing a few connectors does not break
        // this — but a gross truncation (the real failure this guards) drops well below it. The exact
        // empty-modes-still-shipped guarantee is locked deterministically by the assembler unit tests
        // (a not-derived connector is emitted as an entry) and by the byte-lock in the refresh job.
        assertThat(catalog.ids()).hasSizeGreaterThan(70);
        assertThat(catalog.ids()).contains("mysql", "kafka", "mongodb", "doris");
    }

    @Test
    void reconstructsEveryEntryWithItsId() {
        for (String id : catalog.ids()) {
            ConnectorCatalogEntry entry = catalog.byId(id);
            assertThat(entry.id()).isEqualTo(id);
            assertThat(entry.group()).isNotNull();
            assertThat(entry.provenance().connectorRepoSha()).isNotBlank();
        }
    }

    /**
     * The eighteen connectors whose modes come only from an upstream declaration, pinned by name and by
     * value. Regenerating the catalog against an upstream that no longer carries those declarations
     * silently empties every one of them, and nothing else in the build would say so: the byte-lock that
     * would catch it runs only in the connector-present refresh job and skips in an ordinary build.
     *
     * <p>The list is spelled out rather than discovered from the entries themselves, and that is the
     * whole point. A test phrased as "every entry that declares its modes still has some" reads the
     * declaration off the same artifact it is checking, so when the declarations vanish the test finds
     * nothing to check and passes — vacuously green at exactly the moment it was supposed to fire.
     * Naming the ids moves that knowledge out of the artifact and into the test.
     */
    private static final Map<String, List<SourceMode>> DECLARED_ONLY_MODES = Map.ofEntries(
            Map.entry("GitHub", List.of(SourceMode.API)),
            Map.entry("activemq", List.of(SourceMode.STREAM)),
            Map.entry("ali1688", List.of(SourceMode.API)),
            Map.entry("feishu-bitable", List.of(SourceMode.API)),
            Map.entry("hubspot", List.of(SourceMode.API)),
            Map.entry("kafka", List.of(SourceMode.STREAM)),
            Map.entry("kafka_enhanced", List.of(SourceMode.STREAM)),
            Map.entry("lark-approval", List.of(SourceMode.API)),
            Map.entry("lark-doc", List.of(SourceMode.API)),
            Map.entry("metabase", List.of(SourceMode.API)),
            Map.entry("rabbitmq", List.of(SourceMode.STREAM)),
            Map.entry("rocketmq", List.of(SourceMode.STREAM)),
            Map.entry("salesforce", List.of(SourceMode.API)),
            Map.entry("selectdb", List.of(SourceMode.SNAPSHOT)),
            Map.entry("shein", List.of(SourceMode.API)),
            Map.entry("temu", List.of(SourceMode.API)),
            Map.entry("yashandb", List.of(SourceMode.SNAPSHOT)),
            Map.entry("zoho-crm", List.of(SourceMode.API)));

    @Test
    void keepsTheModesThatOnlyAnUpstreamDeclarationCanSupply() {
        // Pinning the count as well as the entries: shrinking the list is the cheapest way to make a
        // failing run go green, and it would leave a test that still looks like it guards eighteen.
        assertThat(DECLARED_ONLY_MODES).as("the pinned set must stay whole").hasSize(18);

        assertThat(catalog.ids()).containsAll(DECLARED_ONLY_MODES.keySet());
        for (Map.Entry<String, List<SourceMode>> expected : DECLARED_ONLY_MODES.entrySet()) {
            assertThat(catalog.byId(expected.getKey()).modes())
                    .as("connector '%s' lost the modes only an upstream declaration supplies — "
                            + "a regeneration against an upstream missing those declarations empties them",
                            expected.getKey())
                    .containsExactlyElementsOf(expected.getValue());
        }
    }

    @Test
    void derivesModesForADatabaseConnector() {
        ConnectorCatalogEntry mysql = catalog.byId("mysql");
        assertThat(mysql.group()).isEqualTo(ConnectorGroup.DATABASE);
        assertThat(mysql.modes()).contains(io.tapstate.core.model.SourceMode.SNAPSHOT,
                io.tapstate.core.model.SourceMode.CDC);
        assertThat(mysql.sink().capable()).isTrue();
    }

    @Test
    void derivesModesForPostgres() {
        // Both modes must come from the capability probe rather than a spec declaration. A
        // hand-written declaration would satisfy an assertion on modes alone while proving nothing
        // about the jar being readable, which is the thing that was broken: an encrypted artifact is
        // not a zip, so it cannot be classloaded to probe at all.
        ConnectorCatalogEntry postgres = catalog.byId("postgres");
        assertThat(postgres.group()).isEqualTo(ConnectorGroup.DATABASE);
        assertThat(postgres.modes()).contains(SourceMode.SNAPSHOT, SourceMode.CDC);
        assertThat(postgres.provenance().modeSource())
                .containsEntry(SourceMode.SNAPSHOT, ModeSource.DERIVED)
                .containsEntry(SourceMode.CDC, ModeSource.DERIVED);
    }

    @Test
    void everyIndexedIdIsResolvable() {
        List<String> ids = catalog.ids();
        assertThat(ids).allSatisfy(id -> assertThat(catalog.byId(id)).isNotNull());
    }
}
