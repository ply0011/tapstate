package io.tapstate.tools.catalog.assembler;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.tapstate.core.catalog.ConnectorCatalogEntry;
import io.tapstate.core.catalog.Provenance;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * What one scan of the upstream specifications found, against what the catalog last recorded.
 */
class SpecDriftTest {

    private static final String MYSQL_SPEC = "{\"properties\":{\"id\":\"mysql\"}}";
    private static final String MYSQL_PATH = "connectors/mysql-connector/src/main/resources/spec_mysql.json";
    private static final String KAFKA_SPEC = "{\"properties\":{\"id\":\"kafka\",\"name\":\"Kafka\"}}";
    private static final String KAFKA_PATH = "connectors/kafka-connector/src/main/resources/spec_kafka.json";

    @Test
    void reportsARowWhoseUpstreamFileIsNoLongerThere() {
        List<ConnectorCatalogEntry> snapshot = List.of(row("mysql", MYSQL_PATH, MYSQL_SPEC));

        SpecDrift.Report report = SpecDrift.compare(snapshot, Map.of());

        assertThat(report.vanishedIds()).containsExactly("mysql");
    }

    private static ConnectorCatalogEntry row(String id, String specPath, String specContent) {
        return new ConnectorCatalogEntry(id, id, id, null, null, List.of(), null, null, false, List.of(),
                new Provenance(null, null, specPath, SpecHash.of(specContent), null, null, null));
    }

    @Test
    void reportsOnlyTheRowWhoseUpstreamContentMoved() {
        List<ConnectorCatalogEntry> snapshot = List.of(
                row("mysql", MYSQL_PATH, MYSQL_SPEC),
                row("kafka", KAFKA_PATH, KAFKA_SPEC));

        SpecDrift.Report report = SpecDrift.compare(snapshot, Map.of(
                MYSQL_PATH, MYSQL_SPEC,
                KAFKA_PATH, KAFKA_SPEC.replace("Kafka", "Apache Kafka")));

        assertThat(report.changedIds()).containsExactly("kafka");
    }

    @Test
    void reportsAnUpstreamConnectorNoRowClaims() {
        List<ConnectorCatalogEntry> snapshot = List.of(row("mysql", MYSQL_PATH, MYSQL_SPEC));

        SpecDrift.Report report = SpecDrift.compare(snapshot, Map.of(
                MYSQL_PATH, MYSQL_SPEC,
                "connectors-unpackage/hudi-connector/src/main/resources/spec_hudi.json",
                "{\"properties\":{\"id\":\"hudi\"}}"));

        assertThat(report.newConnectorIds()).containsExactly("hudi");
    }

    @Test
    void countsNoConnectorInAnUpstreamFileThatCarriesNoId() {
        List<ConnectorCatalogEntry> snapshot = List.of(row("mysql", MYSQL_PATH, MYSQL_SPEC));

        SpecDrift.Report report = SpecDrift.compare(snapshot, Map.of(
                MYSQL_PATH, MYSQL_SPEC,
                "connectors-javascript/github-connector/src/main/resources/postman_api_collection.json",
                "{\"item\":[]}"));

        assertThat(report.newConnectorIds()).isEmpty();
    }

    @Test
    void countsNoConnectorForAnIdThatIsNotShapedLikeOne() {
        // This id is a string an unrelated project chose, and it leaves here for a report read as
        // key=value and, from there, for a pull request body. Carrying a newline it is not a
        // connector nobody catalogued - it is extra lines in whatever reads the report next, which
        // on the drift lane is $GITHUB_OUTPUT, where a second decision= would gate the run open.
        List<ConnectorCatalogEntry> snapshot = List.of(row("mysql", MYSQL_PATH, MYSQL_SPEC));

        SpecDrift.Report report = SpecDrift.compare(snapshot, Map.of(
                MYSQL_PATH, MYSQL_SPEC,
                "connectors/odd-connector/src/main/resources/spec.json",
                "{\"properties\":{\"id\":\"odd\\ndecision=OPEN\"}}"));

        assertThat(report.newConnectorIds()).isEmpty();
    }
}
