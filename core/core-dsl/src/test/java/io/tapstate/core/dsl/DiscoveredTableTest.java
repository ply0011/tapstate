package io.tapstate.core.dsl;

import io.tapstate.core.common.TapstateType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The key a table declares travels with its columns, because a rule that asks whether a write can be
 * matched to a row has to read it from the same place it reads the columns.
 */
class DiscoveredTableTest {

    private static final Map<String, TapstateType> COLUMNS =
            Map.of("id", TapstateType.INT64, "email", TapstateType.STRING);

    @Test
    @DisplayName("a table carries the key it was discovered with, in the order the source declared it")
    void carriesTheDeclaredKeyInOrder() {
        DiscoveredTable table = new DiscoveredTable("orders", COLUMNS, List.of("tenant", "id"), null);

        assertThat(table.primaryKey()).containsExactly("tenant", "id");
    }

    @Test
    @DisplayName("a table whose source declares no key carries an empty key, never null")
    void aTableWithoutAKeyCarriesAnEmptyKey() {
        DiscoveredTable declaredNone = new DiscoveredTable("events", COLUMNS, List.of(), null);
        DiscoveredTable nullKey = new DiscoveredTable("events", COLUMNS, null, null);

        assertThat(declaredNone.primaryKey()).isEmpty();
        assertThat(nullKey.primaryKey()).isEmpty();
    }

    @Test
    @DisplayName("the key is a defensive copy, so a caller's later edit cannot change what a rule reads")
    void theKeyIsADefensiveCopy() {
        List<String> mutable = new ArrayList<>(List.of("id"));

        DiscoveredTable table = new DiscoveredTable("orders", COLUMNS, mutable, null);
        mutable.add("smuggled");

        assertThat(table.primaryKey()).containsExactly("id");
        assertThatThrownBy(() -> table.primaryKey().add("also-smuggled"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("the shorter forms default the key to empty rather than to null")
    void theShorterFormsDefaultTheKeyToEmpty() {
        assertThat(new DiscoveredTable("orders", COLUMNS).primaryKey()).isEmpty();
        assertThat(new DiscoveredTable("orders", COLUMNS, 7L).primaryKey()).isEmpty();
    }

    /**
     * A table nobody discovered is absent from the list a source resolves to; a table discovered and
     * found to declare no key is present with an empty key. Only the second is a table the product can
     * say anything about, so the two must stay distinguishable - collapsing them would let a rule
     * report "this table has no key" about a table it never saw.
     */
    @Test
    @DisplayName("a discovered table declaring no key is not the same as a table that was never discovered")
    void anEmptyKeyIsNotAnUndiscoveredTable() {
        Map<String, DiscoveredTable> discovered = new LinkedHashMap<>();
        discovered.put("events", new DiscoveredTable("events", COLUMNS, List.of(), null));

        assertThat(discovered.get("events")).isNotNull();
        assertThat(discovered.get("events").primaryKey()).isEmpty();
        assertThat(discovered.get("never_discovered")).isNull();
    }
}
