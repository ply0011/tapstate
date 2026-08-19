package io.tapstate.cli;

import io.tapstate.core.catalog.TapstateCatalog;
import io.tapstate.core.model.SourceMode;
import io.tapstate.core.model.SourceResource;
import io.tapstate.core.model.canonical.CanonicalWriter;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The interactive source wizard's question flow, driven by a scripted prompter. Asserts on the
 * canonical artifact the collected answers produce — the wizard's job is to build a valid resource.
 */
class SourceWizardTest {

    /**
     * A connector the catalog resolves no source mode for, so the wizard skips the mode question and
     * builds a pure connection supplier. Which connector that is belongs to the checked-in catalog,
     * not to the wizard.
     */
    private static final String NO_MODE_CONNECTOR = "elasticsearch";

    private static String yaml(SourceResource src) {
        return new CanonicalWriter().write(src);
    }

    @Test
    void theConnectionSupplierFixtureStillHasNoModes() {
        // The two connection-supplier tests need a connector the catalog gives no modes at all, and
        // that is a property of the checked-in catalog rather than of the wizard: a refresh can take
        // it away. postgres was this fixture until its capabilities became derivable, and the way
        // that surfaced was unreadable - the wizard asked one extra question, every scripted answer
        // shifted by one, and the id looked wrong for no visible reason. Assert the premise directly
        // so the next time it changes there is one failure that says what to do.
        assertThat(TapstateCatalog.load().byId(NO_MODE_CONNECTOR).modes())
                .as("pick another connector with no modes for the connection-supplier fixture")
                .isEmpty();
    }

    @Test
    void buildsAConnectionSupplierSourceWhenTheConnectorHasNoModes() {
        // an empty capability matrix -> no mode question -> a pure connection supplier
        ScriptedPrompter p = new ScriptedPrompter(NO_MODE_CONNECTOR, "src_es");
        SourceResource src = new SourceWizard(p, TapstateCatalog.load()).run();
        assertThat(yaml(src)).isEqualTo(
                """
                version: tapstate/v1
                kind: source
                id: src_es
                connector: elasticsearch
                """);
    }

    @Test
    void offersAndWritesOnlyTheConnectorsSupportedModes() {
        // mongodb's trustworthy matrix is [cdc, snapshot]; the wizard offers exactly those (plus a
        // "no mode" choice for a connection-supplier source) and writes the picked mode. The tables
        // question follows the mode; a blank answer leaves the source reading every table.
        ScriptedPrompter p = new ScriptedPrompter("mongodb", "cdc", "", "src_mg");
        SourceResource src = new SourceWizard(p, TapstateCatalog.load()).run();

        assertThat(yaml(src)).isEqualTo(
                """
                version: tapstate/v1
                kind: source
                id: src_mg
                connector: mongodb
                mode: cdc
                """);
        // the mode question (the 2nd choose, after the connector list) is pruned to the matrix
        assertThat(p.offered.get(1)).containsExactly("cdc", "snapshot", "(none)");
    }

    @Test
    void collectsConnectorConfigIntoTheSource() {
        // mysql asks deploymentMode (enum) then host; the rest of the spec's fields skip (lenient).
        // a blank tables answer (after the mode) keeps every table in scope
        ScriptedPrompter p = new ScriptedPrompter("mysql", "cdc", "", "src_my", "standalone", "10.0.0.1");
        SourceResource src = new SourceWizard(p, TapstateCatalog.load()).run();

        assertThat(src.connector()).isEqualTo("mysql");
        assertThat(src.mode()).isEqualTo(SourceMode.CDC);
        assertThat(src.config())
                .containsEntry("deploymentMode", "standalone")
                .containsEntry("host", "10.0.0.1");
    }

    @Test
    void collectsTablesWhenAReadModeIsChosen() {
        // with a read mode picked the wizard asks which tables to read: bare names are literal links,
        // /regex/ tokens are dynamic links; the spec's config fields then skip (lenient)
        ScriptedPrompter p = new ScriptedPrompter("mysql", "cdc", "orders, /audit_.*/", "src_my");
        SourceResource src = new SourceWizard(p, TapstateCatalog.load()).run();
        assertThat(yaml(src)).isEqualTo(
                """
                version: tapstate/v1
                kind: source
                id: src_my
                connector: mysql
                mode: cdc
                tables: [orders, /audit_.*/]
                """);
    }

    @Test
    void asksNoTablesForAConnectionSupplierSource() {
        // no modes -> no read mode -> no tables question. Were tables wrongly asked, the "src_es"
        // answer would be eaten as a table name and the id would fall back to its default.
        ScriptedPrompter p = new ScriptedPrompter(NO_MODE_CONNECTOR, "src_es");
        SourceResource src = new SourceWizard(p, TapstateCatalog.load()).run();
        assertThat(src.tables()).isNull();
        assertThat(src.id()).isEqualTo("src_es");
    }
}

