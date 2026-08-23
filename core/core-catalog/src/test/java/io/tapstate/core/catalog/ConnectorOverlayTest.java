package io.tapstate.core.catalog;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.tapstate.core.model.WriteMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * This repository's own mode declarations for the connectors it ships. Every way of being wrong here
 * fails loudly on purpose: the failure this guards against is a declaration quietly going missing,
 * and a loader that fell back to derivation would reproduce that exact symptom while looking healthy.
 */
class ConnectorOverlayTest {

    private static final String INDEX = "/catalog/overlay/pdk/index.json";

    private static Map<String, String> files(String... pathsAndBodies) {
        Map<String, String> m = new HashMap<>();
        for (int i = 0; i < pathsAndBodies.length; i += 2) {
            m.put(pathsAndBodies[i], pathsAndBodies[i + 1]);
        }
        return m;
    }

    @Test
    void readsTheModesThisRepositoryDeclares() {
        ConnectorOverlay overlay = ConnectorOverlay.read(files(
                INDEX, "[\"kafka\"]",
                "/catalog/overlay/pdk/kafka.json", "{\"modes\": [\"stream\"]}")::get);

        assertThat(overlay.modesFor("kafka")).containsExactly("stream");
        assertThat(overlay.ids()).containsExactly("kafka");
    }

    @Test
    void aConnectorWeDeclareNothingForIsSimplyAbsent() {
        // Absent is not an error - most connectors derive their modes and always will.
        ConnectorOverlay overlay = ConnectorOverlay.read(files(INDEX, "[]")::get);

        assertThat(overlay.modesFor("mysql")).isNull();
    }

    @Test
    void aMissingOverlayIndexIsFatalRatherThanAnEmptyOverlay() {
        // Falling back to "no declarations" is byte-for-byte the symptom of the declarations being
        // wiped, which is the thing this whole mechanism exists to make impossible to miss.
        assertThatThrownBy(() -> ConnectorOverlay.read(files()::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(INDEX);
    }

    @Test
    void anIndexedConnectorWithNoFileIsFatal() {
        assertThatThrownBy(() -> ConnectorOverlay.read(files(INDEX, "[\"kafka\"]")::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kafka");
    }

    @Test
    void anEmptyModeListIsFatal() {
        // "We declare it supports nothing" and "we declare it" cannot be the same spelling. An empty
        // list resolves to zero modes, and zero modes makes the mode check return early - so the
        // connector's validation would be switched off silently, while every other gate stayed green.
        assertThatThrownBy(() -> ConnectorOverlay.read(files(
                INDEX, "[\"kafka\"]",
                "/catalog/overlay/pdk/kafka.json", "{\"modes\": []}")::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kafka");
    }

    @Test
    void anEntryWithoutAModesKeyIsFatal() {
        assertThatThrownBy(() -> ConnectorOverlay.read(files(
                INDEX, "[\"kafka\"]",
                "/catalog/overlay/pdk/kafka.json", "{}")::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("kafka");
    }

    @Test
    void anIndexThatIsNotAnArrayIsFatal() {
        assertThatThrownBy(() -> ConnectorOverlay.read(files(INDEX, "{\"kafka\": []}")::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(INDEX);
    }

    @Test
    void theBundledOverlayLoads() {
        // The shipped resource, read the way the assembler reads it. Guards the layout itself: a file
        // moved out from under the glob would pass every test above and fail only here.
        assertThat(ConnectorOverlay.load().ids()).isNotEmpty();
    }

    @Test
    void readsTheSinkThisRepositoryDeclares() {
        // Modes are not the only face that has to be declarable. Sink capability is read off the
        // write_record capability, which needs the connector's jar - so for a connector this
        // repository cannot build, the only honest source for it is a declaration.
        ConnectorOverlay overlay = ConnectorOverlay.read(files(
                INDEX, "[\"greenplum\"]",
                "/catalog/overlay/pdk/greenplum.json",
                "{\"modes\": [\"snapshot\"], \"sink\": {\"capable\": true,"
                        + " \"writeSemantics\": [\"upsert\", \"append\"]}}")::get);

        assertThat(overlay.sinkFor("greenplum").capable()).isTrue();
        assertThat(overlay.sinkFor("greenplum").writeSemantics())
                .containsExactly(WriteMode.UPSERT, WriteMode.APPEND);
    }

    @Test
    void aConnectorDeclaringOnlyModesLeavesTheSinkToDerivation() {
        // The sink block is optional, and absent has to mean "derive it" rather than "no sink":
        // every entry that predates the block declares modes only, and reading those as sink-less
        // would silently turn eighteen connectors into non-targets.
        ConnectorOverlay overlay = ConnectorOverlay.read(files(
                INDEX, "[\"kafka\"]",
                "/catalog/overlay/pdk/kafka.json", "{\"modes\": [\"stream\"]}")::get);

        assertThat(overlay.sinkFor("kafka")).isNull();
    }

    @Test
    void aSinkDeclaringItIsNotCapableIsRefused() {
        // Same reasoning as an empty modes list: "we declare it can sink nothing" and "we did not
        // declare a sink" are one written form apart and behave identically downstream, so the one
        // that says nothing has to be the absent block rather than a present, false one.
        assertThatThrownBy(() -> ConnectorOverlay.read(files(
                INDEX, "[\"greenplum\"]",
                "/catalog/overlay/pdk/greenplum.json",
                "{\"modes\": [\"snapshot\"], \"sink\": {\"capable\": false}}")::get))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("greenplum");
    }
}
