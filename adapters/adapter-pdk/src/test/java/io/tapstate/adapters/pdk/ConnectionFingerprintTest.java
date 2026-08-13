package io.tapstate.adapters.pdk;

import static org.assertj.core.api.Assertions.assertThat;

import io.tapstate.spi.store.ConnectionConfig;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The key a pooled connector instance is filed under: the connector plus the exact settings it was
 * opened with. What it must and must not depend on is the whole point — a key that follows the
 * connection's identity hands a caller an instance still holding the settings it was opened with
 * after those settings changed, and a key that carries the settings themselves puts a password in
 * every place the key is held.
 */
class ConnectionFingerprintTest {

    private static ConnectionConfig config(String id, Map<String, Object> settings) {
        return new ConnectionConfig(id, "mongodb", settings);
    }

    @Test
    void twoConnectionsWithTheSameConnectorAndSettingsShareOneKey() {
        // Not the connection id: two connections pointing at the same place are the same instance to
        // open, and keying by identity would open a second one for no reason.
        String left = ConnectionFingerprint.of(config("conn-a", Map.of("uri", "mongodb://host/db")));
        String right = ConnectionFingerprint.of(config("conn-b", Map.of("uri", "mongodb://host/db")));

        assertThat(left).isEqualTo(right);
    }

    @Test
    void changingASettingChangesTheKey() {
        // The whole reason the settings are in the key: an applied config change must not keep being
        // served by an instance holding the old URI, which would read the old database and report no error.
        String before = ConnectionFingerprint.of(config("conn-a", Map.of("uri", "mongodb://host/db")));
        String after = ConnectionFingerprint.of(config("conn-a", Map.of("uri", "mongodb://host/other")));

        assertThat(before).isNotEqualTo(after);
    }

    @Test
    void theOrderSettingsWereWrittenInDoesNotChangeTheKey() {
        // Settings arrive as an ordered map, so an unsorted rendering makes the same settings hash two
        // ways and opens a second instance for a connection that did not change. Nested maps are the
        // case that survives a top-level sort, so the nesting is what this pins.
        Map<String, Object> writtenFirst = new LinkedHashMap<>();
        writtenFirst.put("enabled", true);
        writtenFirst.put("ca", "root.pem");
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("uri", "mongodb://host/db");
        first.put("tls", writtenFirst);

        Map<String, Object> writtenSecond = new LinkedHashMap<>();
        writtenSecond.put("ca", "root.pem");
        writtenSecond.put("enabled", true);
        Map<String, Object> second = new LinkedHashMap<>();
        second.put("tls", writtenSecond);
        second.put("uri", "mongodb://host/db");

        assertThat(ConnectionFingerprint.of(config("conn-a", first)))
                .isEqualTo(ConnectionFingerprint.of(config("conn-a", second)));
    }

    @Test
    void listSettingsKeepTheirOrder() {
        // A list is ordered by meaning - a seed list is not the same connection reordered - so unlike a
        // map it must not be sorted away.
        String first = ConnectionFingerprint.of(config("conn-a", Map.of("hosts", List.of("a", "b"))));
        String second = ConnectionFingerprint.of(config("conn-a", Map.of("hosts", List.of("b", "a"))));

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void theKeyCarriesNoSettingValue() {
        // Settings hold credentials, and the key is held in a map, named in diagnostics and read while
        // debugging. Hashing is what keeps a password out of all three.
        String key = ConnectionFingerprint.of(config("conn-a",
                Map.of("uri", "mongodb://user:hunter2@host/db", "password", "hunter2")));

        assertThat(key).doesNotContain("hunter2").doesNotContain("mongodb://");
        assertThat(key).matches("mongodb:[0-9a-f]{64}");
    }
}
