package io.tapstate.tools.catalog.assembler;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.tapstate.core.catalog.ConnectorOverlay;
import io.tapstate.core.catalog.ConnectorCatalogEntry;
import io.tapstate.core.catalog.ConnectorGroup;
import io.tapstate.core.model.SourceMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The assembler drives the core normalizer and merge rules over a walk result and a derived
 * capability bitmap, and accounts for every degradation in the report: a database derives its modes,
 * an undeclared message-queue connector is flagged (derived cdc is wrong for it), a JavaScript
 * connector with no derivable capability is unclassified, and a sink with no DML signal is recorded
 * as defaulted — none silently.
 */
class CatalogAssemblerTest {

    private static final String SHA = "20371556";
    // Deliberately not equal to SHA: the two faces are refreshed by different jobs, so a test that
    // passed the same value twice would pass just as well against an implementation that used one
    // of them for both.
    private static final String CAP_SHA = "9f0c11ab";

    // batch + stream + write — a database registers all three.
    private static final Set<String> DB_CAPS =
            Set.of("batch_read_function", "stream_read_function", "write_record_function");

    private static final String MYSQL_SPEC = """
            {"properties":{"id":"mysql","name":"Mysql","realName":"MySQL","icon":"icons/mysql.png",
              "tags":["Database"]},
             "configOptions":{"capabilities":[
                {"id":"dml_insert_policy","alternatives":["update_on_exists","just_insert"]},
                {"id":"dml_update_policy"}],
               "connection":{"properties":{"host":{"type":"string","title":"Host","required":true}}}},
             "messages":{"default":"en_US","en_US":{}}}
            """;

    // Kafka: registers batch+stream+write like a db, but declares no tapstate.modes and has no dml block.
    private static final String KAFKA_SPEC = """
            {"properties":{"id":"kafka","name":"Kafka","realName":"Apache Kafka","icon":"icons/kafka.png",
              "tags":["Database"]},
             "configOptions":{"connection":{"properties":{}}},
             "messages":{"default":"en_US","en_US":{}}}
            """;

    private static final String GITHUB_SPEC = """
            {"properties":{"id":"github","name":"GitHub","icon":"icon/github.png","tags":["SaaS"]},
             "configOptions":{"connection":{"properties":{}}},
             "messages":{"default":"en_US","en_US":{}}}
            """;

    private static final String KAFKA_SPEC_DECLARING_CDC = """
            {"properties":{"id":"kafka","name":"Kafka","realName":"Apache Kafka","icon":"icons/kafka.png",
              "tags":["Database"]},
             "tapstate":{"modes":["cdc"]},
             "configOptions":{"connection":{"properties":{}}},
             "messages":{"default":"en_US","en_US":{}}}
            """;

    private static final String KAFKA_SPEC_PATH =
            "connectors/kafka-connector/src/main/resources/spec_kafka.json";

    private Assembly assemble() {
        return assemble(noOverlay());
    }

    private Assembly assemble(ConnectorOverlay overlay) {
        return assemble(overlay, Map.of());
    }

    private Assembly assemble(ConnectorOverlay overlay, Map<String, String> specOverrides) {
        List<ConnectorSource> sources = List.of(
                new ConnectorSource("mysql", "mysql-connector",
                        "connectors/mysql-connector/src/main/resources/mysql-spec.json",
                        "io.tapdata.connector.mysql.MysqlConnector", false),
                new ConnectorSource("kafka", "kafka-connector",
                        "connectors/kafka-connector/src/main/resources/spec_kafka.json",
                        "io.tapdata.connector.kafka.KafkaConnector", false),
                new ConnectorSource("github", "github-connector",
                        "connectors-javascript/github-connector/src/main/resources/spec.json",
                        null, true));
        List<Exemption> exemptions =
                List.of(new Exemption(Exemption.Category.EXCLUDED, "tdd-connector", "known non-connector module"));
        WalkResult walk = new WalkResult(sources, exemptions);

        Map<String, Set<String>> bitmap = Map.of("mysql", DB_CAPS, "kafka", DB_CAPS);
        Map<String, String> specs = Map.of(
                "connectors/mysql-connector/src/main/resources/mysql-spec.json", MYSQL_SPEC,
                "connectors/kafka-connector/src/main/resources/spec_kafka.json", KAFKA_SPEC,
                "connectors-javascript/github-connector/src/main/resources/spec.json", GITHUB_SPEC);

        Map<String, String> merged = new java.util.HashMap<>(specs);
        merged.putAll(specOverrides);

        return CatalogAssembler.assemble(walk, SHA, CAP_SHA, bitmap, overlay, merged::get);
    }

    @Test
    void derivesDatabaseModesAndSinkFromCapabilities() {
        ConnectorCatalogEntry mysql = entry("mysql");
        assertThat(mysql.modes()).containsExactlyInAnyOrder(SourceMode.SNAPSHOT, SourceMode.CDC);
        assertThat(mysql.group()).isEqualTo(ConnectorGroup.DATABASE);
        assertThat(mysql.sink().capable()).isTrue();
    }

    @Test
    void entriesAreSortedByIdAndIndexedInTheReport() {
        assertThat(assemble().entries()).extracting(ConnectorCatalogEntry::id)
                .containsExactly("github", "kafka", "mysql");
        assertThat(assemble().report().ingestedIds()).containsExactly("github", "kafka", "mysql");
    }

    @Test
    void flagsAnUndeclaredMessageQueueConnector() {
        // Kafka's name routes it to the MQ group, but with no declaration it derived cdc/snapshot —
        // wrong for a stream source. That must be surfaced, not silently shipped.
        assertThat(entry("kafka").group()).isEqualTo(ConnectorGroup.MQ);
        assertThat(assemble().report().unverifiedModes()).contains("kafka");
    }

    @Test
    void aConnectorOurOverlayDeclaresIsNoLongerUnverified() {
        // The other half of the same rule: suspicion is about nobody having said what the connector
        // reads, so a declaration of ours answers it exactly as an upstream one would. Without this
        // the report would keep naming every connector we ourselves declared, and a list that always
        // contains the same eighteen names is a list nobody reads.
        assertThat(assemble(overlayDeclaring("kafka", "stream")).report().unverifiedModes())
                .doesNotContain("kafka");
    }


    @Test
    void reportsWhenOurDeclarationDisagreesWithTheConnectorsOwn() {
        Assembly assembly = assemble(overlayDeclaring("kafka", "stream"),
                Map.of(KAFKA_SPEC_PATH, KAFKA_SPEC_DECLARING_CDC));

        assertThat(assembly.report().overlayDivergences())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("kafka").contains("cdc").contains("stream");
    }

    @Test
    void agreementBetweenTheTwoDeclarationsIsNotADivergence() {
        // The discriminating half. Both sources speak and say the same thing, which is the ordinary
        // case for every connector we declare - reporting it would print all eighteen on every run
        // and bury the one line that means something.
        Assembly assembly = assemble(overlayDeclaring("kafka", "cdc"),
                Map.of(KAFKA_SPEC_PATH, KAFKA_SPEC_DECLARING_CDC));

        assertThat(assembly.report().overlayDivergences()).isEmpty();
    }

    @Test
    void reportsAModeWeDeclareThatTheCapabilitiesContradict() {
        // github has no capabilities in the bitmap at all, so a claim of cdc is one nothing supports.
        // No other gate sees this: the entry exists, so validation trusts it and admits the mode.
        Assembly assembly = assemble(overlayDeclaring("github", "cdc"));

        assertThat(assembly.report().overlayNotDerivable())
                .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
                .contains("github").contains("cdc");
    }

    @Test
    void anUnderivableModeIsNeverReportedAsUnsupported() {
        // stream, api and file cannot be derived from capabilities by construction - that is exactly
        // why they have to be declared. Flagging them would flag every connector we declare, so the
        // check is deliberately narrow enough to stay silent here.
        Assembly assembly = assemble(overlayDeclaring("github", "api"));

        assertThat(assembly.report().overlayNotDerivable()).isEmpty();
    }

    private static ConnectorOverlay noOverlay() {
        return ConnectorOverlay.read(Map.of("/catalog/overlay/pdk/index.json", "[]")::get);
    }

    private static ConnectorOverlay overlayDeclaring(String id, String mode) {
        return ConnectorOverlay.read(Map.of(
                "/catalog/overlay/pdk/index.json", "[\"" + id + "\"]",
                "/catalog/overlay/pdk/" + id + ".json", "{\"modes\":[\"" + mode + "\"]}")::get);
    }

    @Test
    void reportsAConnectorWithNoResolvableModeAsUnclassified() {
        assertThat(entry("github").modes()).isEmpty();
        assertThat(assemble().report().unclassified()).containsExactly("github");
    }

    @Test
    void recordsASinkDefaultedWithNoDmlSignal() {
        // Kafka is write-capable (derived) but its spec carries no dml policy, so the write semantics
        // were a defaulted superset with no signal — recorded as such. MySQL has dml signals, so it is not.
        assertThat(assemble().report().sinkDefaultedNoSignal()).contains("kafka").doesNotContain("mysql");
    }

    @Test
    void carriesWalkExemptionsIntoTheReport() {
        assertThat(assemble().report().exemptions())
                .anyMatch(e -> e.category() == Exemption.Category.EXCLUDED && e.module().equals("tdd-connector"));
    }

    @Test
    void reportsAJavaConnectorAbsentFromTheBitmapAsNotDerivedNotUnclassified() {
        // hazelcast-connector has a class but is absent from the bitmap — it was not derived this
        // refresh (no jar built, or its jar would not classload). That is a distinct gap from an
        // undeclared connector, so it is reported as "not derived", not lumped into unclassified.
        String hazelcastSpec = """
                {"properties":{"id":"hazelcast","name":"Hazelcast","icon":"icons/hazelcast.png",
                  "tags":["Cache"]},
                 "configOptions":{"connection":{"properties":{}}},
                 "messages":{"default":"en_US","en_US":{}}}
                """;
        List<ConnectorSource> sources = List.of(
                new ConnectorSource("hazelcast", "hazelcast-connector",
                        "connectors/hazelcast-connector/src/main/resources/spec_hazelcast.json",
                        "io.tapdata.connector.hazelcast.HazelcastConnector", false));
        WalkResult walk = new WalkResult(sources, List.of());

        Assembly assembly = CatalogAssembler.assemble(walk, SHA, CAP_SHA, Map.of(), noOverlay(),
                Map.of("connectors/hazelcast-connector/src/main/resources/spec_hazelcast.json",
                        hazelcastSpec)::get);

        assertThat(assembly.report().notDerived()).containsExactly("hazelcast");
        assertThat(assembly.report().unclassified()).doesNotContain("hazelcast");
        // Zero silent drops is two-part: flagged in the report AND still shipped as a (modeless) entry.
        ConnectorCatalogEntry hazelcast = assembly.entries().stream()
                .filter(e -> e.id().equals("hazelcast")).findFirst().orElseThrow();
        assertThat(hazelcast.modes()).isEmpty();
        assertThat(hazelcast.sink().capable()).isFalse();
    }

    @Test
    void flagsAFileConnectorWhoseModesNobodyDeclared() {
        // A CSV file does not support cdc. The capability probe cannot tell a file reader from a
        // database - it sees batch and stream functions and resolves cdc/snapshot for both - so an
        // undeclared file connector ships a mode it cannot honour. Same failure as the message-queue
        // case, only the group differs, and the group is not what makes it wrong.
        Assembly assembly = assembleOne("csv", "File", "io.tapdata.connector.csv.CsvConnector", DB_CAPS);

        assertThat(only(assembly).group()).isEqualTo(ConnectorGroup.FILE);
        assertThat(only(assembly).modes()).containsExactlyInAnyOrder(SourceMode.CDC, SourceMode.SNAPSHOT);
        assertThat(assembly.report().unverifiedModes()).contains("csv");
    }

    @Test
    void flagsAConnectorDerivedAsSnapshotOnly() {
        // Batch-read only, so nothing about it looks like a stream - and that is exactly why a check
        // keyed on the stream capability cannot see it. What makes this connector suspect is that
        // nobody said what it reads, not which capabilities it happened to register.
        Assembly assembly = assembleOne("quickapi", "SaaS", "io.tapdata.connector.quickapi.QuickApiConnector",
                Set.of("batch_read_function"));

        assertThat(only(assembly).group()).isEqualTo(ConnectorGroup.SAAS);
        assertThat(only(assembly).modes()).containsExactly(SourceMode.SNAPSHOT);
        assertThat(assembly.report().unverifiedModes()).contains("quickapi");
    }

    @Test
    void aDatabaseIsNeverASuspect() {
        // The discriminating half. A database is the one group derivation is trustworthy for: cdc and
        // snapshot are what a database does, so deriving them is an answer, not a guess. A check that
        // flagged every undeclared connector would name most of the catalog and be read by nobody.
        assertThat(entry("mysql").group()).isEqualTo(ConnectorGroup.DATABASE);
        assertThat(assemble().report().unverifiedModes()).doesNotContain("mysql");
    }

    @Test
    void aConnectorWithNoModesAtAllBelongsToTheOtherBucket() {
        // The two buckets are disjoint by construction: nothing was derived here, so there is no
        // wrong mode to review - the gap is that it has no mode at all, which unclassified already
        // says. Listing it in both would double-count every JavaScript connector.
        assertThat(entry("github").modes()).isEmpty();
        assertThat(assemble().report().unclassified()).contains("github");
        assertThat(assemble().report().unverifiedModes()).doesNotContain("github");
    }

    /** One connector, its own walk and bitmap - so a case cannot disturb the shared three-connector
     *  fixture the ordering and indexing assertions pin. */
    private static Assembly assembleOne(String id, String tag, String connectorClassFqn, Set<String> caps) {
        String spec = """
                {"properties":{"id":"%s","name":"%s","icon":"icons/%s.png","tags":["%s"]},
                 "configOptions":{"connection":{"properties":{}}},
                 "messages":{"default":"en_US","en_US":{}}}
                """.formatted(id, id, id, tag);
        String path = "connectors/" + id + "-connector/src/main/resources/spec_" + id + ".json";
        WalkResult walk = new WalkResult(
                List.of(new ConnectorSource(id, id + "-connector", path, connectorClassFqn, false)), List.of());
        return CatalogAssembler.assemble(walk, SHA, CAP_SHA, Map.of(id, caps), noOverlay(), Map.of(path, spec)::get);
    }

    private static ConnectorCatalogEntry only(Assembly assembly) {
        assertThat(assembly.entries()).hasSize(1);
        return assembly.entries().get(0);
    }

    private ConnectorCatalogEntry entry(String id) {
        return assemble().entries().stream().filter(e -> e.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void aConnectorThisRepositoryCannotBuildIsReportedByNameWithItsReason() {
        // Both buckets hold connectors with no derived capabilities, and they mean opposite things:
        // one is a decision, the other is a connector that stopped being built and nobody noticed.
        // Lumped together, the second is invisible - which is the whole reason the first is named.
        String unbuildable = UnbuildableConnectors.ids().iterator().next();
        String specPath = "connectors/" + unbuildable + "-connector/src/main/resources/spec.json";
        List<ConnectorSource> sources = List.of(
                new ConnectorSource(unbuildable, unbuildable + "-connector", specPath,
                        "io.tapdata.connector.Whatever", false),
                new ConnectorSource("kafka", "kafka-connector",
                        "connectors/kafka-connector/src/main/resources/spec_kafka.json",
                        "io.tapdata.connector.kafka.KafkaConnector", false));
        String spec = MYSQL_SPEC.replace("\"id\":\"mysql\"", "\"id\":\"" + unbuildable + "\"");
        Map<String, String> specs = Map.of(
                specPath, spec,
                "connectors/kafka-connector/src/main/resources/spec_kafka.json", KAFKA_SPEC);

        // Neither is in the bitmap: the unbuildable one because it is never built, kafka because its
        // jar happened not to be there - the two cases this partition has to tell apart.
        IngestReport report = CatalogAssembler
                .assemble(new WalkResult(sources, List.of()), SHA, CAP_SHA, Map.of(), noOverlay(), specs::get)
                .report();

        assertThat(report.notBuilt())
                .containsExactly(unbuildable + ": " + UnbuildableConnectors.reasonFor(unbuildable));
        assertThat(report.notDerived()).containsExactly("kafka");
    }
}
