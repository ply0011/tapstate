package io.tapstate.core.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.tapstate.core.model.SourceMode;

/**
 * Unit-tests the loader assembly via {@link TapstateCatalog#build} with inline entries — index order is
 * preserved, an entry reconstructs fully, an unknown id is rejected, and a duplicated index id fails
 * loud rather than silently dropping an entry. The real bundled catalog is exercised separately by
 * {@link CatalogConsistencyTest}.
 */
class TapstateCatalogTest {

    private static final String MYSQL = """
            {
              "id": "mysql", "name": "Mysql", "displayName": "MySQL", "icon": null,
              "group": "database", "modes": ["snapshot"], "discovery": "catalog",
              "sink": {"capable": false, "writeSemantics": []},
              "pushOut": false, "config": [],
              "provenance": {"specPath": "x", "specContentHash": "x",
                "pdkApiVersion": null, "requiredLevel": null, "modeSource": {"snapshot": "derived"}}
            }
            """;

    private static final String KAFKA = """
            {
              "id": "kafka", "name": "Kafka", "displayName": "Apache Kafka", "icon": null,
              "group": "mq", "modes": ["stream"], "discovery": "catalog",
              "sink": {"capable": true, "writeSemantics": ["upsert", "append"]},
              "pushOut": true, "config": [],
              "provenance": {"specPath": "x", "specContentHash": "x",
                "pdkApiVersion": null, "requiredLevel": null, "modeSource": {"stream": "declared"}}
            }
            """;

    private static final Map<String, String> ENTRIES = Map.of("mysql", MYSQL, "kafka", KAFKA);

    private static final String POSTGRES = """
            {
              "id": "postgres", "name": "Postgres", "displayName": "PostgreSQL", "icon": null,
              "group": "database", "modes": ["snapshot"], "discovery": "catalog",
              "sink": {"capable": true, "writeSemantics": ["upsert"]},
              "pushOut": true, "config": [],
              "provenance": {"specPath": "spec.json", "specContentHash": "h",
                "pdkApiVersion": "1.0.0", "requiredLevel": null, "modeSource": {"snapshot": "derived"}}
            }
            """;

    // Same id as the bundled mysql but a different row — proves a registered row shadows the bundled
    // one whole (id-only identity) and carries the server-filled pdkApiVersion slot.
    private static final String MYSQL_REGISTERED = """
            {
              "id": "mysql", "name": "Mysql", "displayName": "MySQL (registered)", "icon": null,
              "group": "database", "modes": ["snapshot"], "discovery": "catalog",
              "sink": {"capable": false, "writeSemantics": []},
              "pushOut": false, "config": [],
              "provenance": {"specPath": "spec.json", "specContentHash": "h",
                "pdkApiVersion": "1.2.3", "requiredLevel": null, "modeSource": {"snapshot": "derived"}}
            }
            """;

    @Test
    void preservesConnectorIdsInIndexOrder() {
        assertThat(TapstateCatalog.build(index("mysql", "kafka"), ENTRIES::get).ids())
                .containsExactly("mysql", "kafka");
    }

    @Test
    void reconstructsEveryEntryInIndexOrder() {
        assertThat(TapstateCatalog.build(index("mysql", "kafka"), ENTRIES::get).all())
                .extracting(ConnectorCatalogEntry::id).containsExactly("mysql", "kafka");
    }

    @Test
    void resolvesAnEntryByIdAndReconstructsItFully() {
        ConnectorCatalogEntry kafka = TapstateCatalog.build(index("kafka"), ENTRIES::get).byId("kafka");

        assertThat(kafka.group()).isEqualTo(ConnectorGroup.MQ);
        assertThat(kafka.modes()).containsExactly(SourceMode.STREAM);
        assertThat(kafka.pushOut()).isTrue();
        assertThat(kafka.provenance().modeSource()).containsEntry(SourceMode.STREAM, ModeSource.DECLARED);
    }

    @Test
    void rejectsAnUnknownConnectorId() {
        assertThatThrownBy(() -> TapstateCatalog.build(index("mysql"), ENTRIES::get).byId("nope"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsADuplicateIdInTheIndex() {
        // A duplicated index id would desync ids()/all() and silently drop an entry — fail loud.
        assertThatThrownBy(() -> TapstateCatalog.build(index("mysql", "mysql"), ENTRIES::get))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void mergedWithNoRegisteredRowsEqualsTheBundledView() {
        TapstateCatalog bundled = TapstateCatalog.build(index("mysql", "kafka"), ENTRIES::get);

        TapstateCatalog merged = TapstateCatalog.merged(bundled, List.of());

        assertThat(merged.ids()).containsExactly("mysql", "kafka");
        assertThat(merged.all()).extracting(ConnectorCatalogEntry::id).containsExactly("mysql", "kafka");
    }

    @Test
    void mergedAppendsRegisteredOnlyIdsAfterTheBundledIds() {
        TapstateCatalog bundled = TapstateCatalog.build(index("mysql"), ENTRIES::get);

        TapstateCatalog merged = TapstateCatalog.merged(bundled, List.of(CatalogEntryReader.read(POSTGRES)));

        assertThat(merged.ids()).containsExactly("mysql", "postgres");
        assertThat(merged.byId("postgres").displayName()).isEqualTo("PostgreSQL");
    }

    @Test
    void mergedLetsARegisteredRowShadowABundledRowWithTheSameId() {
        TapstateCatalog bundled = TapstateCatalog.build(index("mysql", "kafka"), ENTRIES::get);

        TapstateCatalog merged = TapstateCatalog.merged(bundled, List.of(CatalogEntryReader.read(MYSQL_REGISTERED)));

        // Id-only identity: the id set and its order are unchanged, but the row is the registered one.
        assertThat(merged.ids()).containsExactly("mysql", "kafka");
        assertThat(merged.byId("mysql").displayName()).isEqualTo("MySQL (registered)");
        assertThat(merged.byId("mysql").provenance().pdkApiVersion()).isEqualTo("1.2.3");
    }

    /** An index document naming {@code ids}, carrying the two revisions the head is defined to hold. */
    private static String index(String... ids) {
        return """
                {"specSha": "aaaa1111", "capabilitySha": "bbbb2222", "entries": [%s]}
                """.formatted(Arrays.stream(ids).map(id -> '"' + id + '"').collect(Collectors.joining(", ")));
    }

    @Test
    void stampsBothIndexHeadShasOntoEveryEntry() {
        // The revisions moved out of the entry files into the head, so what a caller reads off an entry
        // has to be unchanged by that move - otherwise every consumer of provenance broke quietly.
        TapstateCatalog catalog = TapstateCatalog.build(index("mysql", "kafka"), ENTRIES::get);

        assertThat(catalog.all()).allSatisfy(entry -> {
            assertThat(entry.provenance().specSha()).isEqualTo("aaaa1111");
            assertThat(entry.provenance().capabilitySha()).isEqualTo("bbbb2222");
        });
    }

    @Test
    void keepsTheTwoShasApartRatherThanStampingOneOnBoth() {
        // The whole reason there are two: a spec-only refresh advances one and leaves the other where
        // the last derivation put it. An implementation reading either key for both passes every test
        // above and fails this one.
        String head = """
                {"specSha": "newspec", "capabilitySha": "oldcaps", "entries": ["mysql"]}
                """;

        Provenance provenance = TapstateCatalog.build(head, ENTRIES::get).byId("mysql").provenance();

        assertThat(provenance.specSha()).isEqualTo("newspec");
        assertThat(provenance.capabilitySha()).isEqualTo("oldcaps");
    }

    @Test
    void refusesAnIndexThatIsStillABareArrayOfIds() {
        // The old shape. Read leniently it would produce a catalog with both revisions null and no sign
        // anything was wrong - the provenance would simply be empty everywhere.
        assertThatThrownBy(() -> TapstateCatalog.build("[\"mysql\"]", ENTRIES::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not a JSON object");
    }

    @Test
    void refusesAnIndexHeadWithNoEntriesArray() {
        assertThatThrownBy(() -> TapstateCatalog.build("{\"specSha\": \"a\"}", ENTRIES::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no entries array");
    }
}
