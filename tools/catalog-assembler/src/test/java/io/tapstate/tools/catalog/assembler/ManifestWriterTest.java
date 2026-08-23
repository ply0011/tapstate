package io.tapstate.tools.catalog.assembler;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The manifest is the probe worklist the PDK-free assembler hands to catalog-derive: one tab-separated
 * line per Java connector ({@code id\tmodule\tclass}), so derive knows what to classload without
 * repeating the walk. A line-oriented hand-off keeps derive dependency-free — it parses with a split,
 * not a JSON library. JavaScript connectors have no class to probe and are left out; lines are in id
 * order so the file is deterministic regardless of how the walk ordered its sources.
 */
class ManifestWriterTest {

    @Test
    void writesOnlyJavaConnectorsAsATabSeparatedWorklistInIdOrder() {
        List<ConnectorSource> sources = List.of(
                new ConnectorSource("mysql", "mysql-connector",
                        "mysql-connector/src/main/resources/mysql-spec.json",
                        "io.tapdata.connector.mysql.MysqlConnector", false),
                new ConnectorSource("kafka", "kafka-connector",
                        "kafka-connector/src/main/resources/spec_kafka.json",
                        "io.tapdata.connector.kafka.KafkaConnector", false),
                new ConnectorSource("GitHub", "github-connector",
                        "github-connector/src/main/resources/spec.json", null, true));

        assertThat(ManifestWriter.write(sources)).isEqualTo("""
                kafka\tkafka-connector\tio.tapdata.connector.kafka.KafkaConnector
                mysql\tmysql-connector\tio.tapdata.connector.mysql.MysqlConnector
                """);
    }

    @Test
    void writesNothingWhenNoConnectorHasAClassToProbe() {
        List<ConnectorSource> jsOnly = List.of(
                new ConnectorSource("GitHub", "github-connector",
                        "github-connector/src/main/resources/spec.json", null, true));

        assertThat(ManifestWriter.write(jsOnly)).isEmpty();
    }

    @Test
    void leavesOutAConnectorThisRepositoryCannotBuild() {
        // The worklist is what the build list is derived from, so a connector that cannot be built
        // has to be absent from it - present, it stops the reactor part-way and nothing downstream
        // gets derived at all.
        String unbuildable = UnbuildableConnectors.ids().iterator().next();
        List<ConnectorSource> sources = List.of(
                new ConnectorSource("mysql", "mysql-connector",
                        "mysql-connector/src/main/resources/mysql-spec.json",
                        "io.tapdata.connector.mysql.MysqlConnector", false),
                new ConnectorSource(unbuildable, unbuildable + "-connector",
                        unbuildable + "-connector/src/main/resources/spec.json",
                        "io.tapdata.connector.Whatever", false));

        assertThat(ManifestWriter.write(sources))
                .doesNotContain(unbuildable)
                .contains("mysql");
    }
}
