package io.tapstate.tools.catalog.assembler;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.tapstate.core.catalog.ConnectorCatalogEntry;
import io.tapstate.core.catalog.Provenance;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The enumeration a drift scan pulls from upstream, decided here rather than in the scan's shell so
 * a witness can drive it.
 */
class SpecPathEnumeratorTest {

    @Test
    void enumeratesTheSpecPathOfEveryCheckedInEntryWhicheverTreeItSitsIn() {
        List<ConnectorCatalogEntry> snapshot = List.of(
                entry("mysql", "connectors/mysql-connector/src/main/resources/spec_mysql.json"),
                entry("GitHub", "connectors-javascript/github-connector/src/main/resources/spec.json"));

        assertThat(SpecPathEnumerator.declaredSpecPaths(snapshot)).containsExactly(
                "connectors-javascript/github-connector/src/main/resources/spec.json",
                "connectors/mysql-connector/src/main/resources/spec_mysql.json");
    }

    @Test
    void offersACandidateSpecFromATreeNoCheckedInRowMentions() {
        List<ConnectorCatalogEntry> snapshot = List.of(
                entry("mysql", "connectors/mysql-connector/src/main/resources/spec_mysql.json"));
        List<String> upstream = List.of(
                "connectors/mysql-connector/src/main/resources/spec_mysql.json",
                "connectors-unpackage/hudi-connector/src/main/resources/spec_hudi.json");

        assertThat(SpecPathEnumerator.specPathsToFetch(snapshot, upstream))
                .contains("connectors-unpackage/hudi-connector/src/main/resources/spec_hudi.json");
    }

    private static ConnectorCatalogEntry entry(String id, String specPath) {
        return new ConnectorCatalogEntry(id, id, id, null, null, List.of(), null, null, false, List.of(),
                new Provenance(null, specPath, null, null, null, null));
    }
}
