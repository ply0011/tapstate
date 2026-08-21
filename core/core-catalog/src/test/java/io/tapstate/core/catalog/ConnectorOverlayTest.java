package io.tapstate.core.catalog;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

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
}
