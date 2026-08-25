package io.tapstate.adapters.pdk;

import io.tapstate.core.catalog.OfficialConnectors;
import io.tapstate.spi.store.ConnectorCapabilities;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The register path and the authoring surfaces have to read one supported-connector list, not two
 * equal ones. A value comparison would pass on the day a copy is made and only start failing once one
 * side is edited — by which point users are already being offered connectors that cannot be
 * installed. So this asserts identity: what the shipped registrar accepts is the very list the
 * catalog module holds.
 */
class OfficialConnectorsSingleSourceTest {

    private static ConnectorArtifactRegistrar registrarAccepting(List<String> alsoAccept) {
        return new ConnectorArtifactRegistrar(
                new InMemoryConnectorRegistry(), new ConnectorIntrospector(),
                id -> new ConnectorCapabilities(Set.of()),
                new InMemoryConnectorCatalogStore(), new InMemoryConnectorSpecStore(), alsoAccept);
    }

    @Test
    void theShippedRegistrarAcceptsTheOneOfficialList() {
        // Identity, not equality: a constant copied back into this module would still be equal.
        assertThat(registrarAccepting(List.of()).acceptedConnectorIds()).isSameAs(OfficialConnectors.IDS);
    }

    @Test
    void aWidenedDeploymentStillStartsFromThatList() {
        List<String> expected = new ArrayList<>(OfficialConnectors.IDS);
        expected.add("orders");

        assertThat(registrarAccepting(List.of("orders")).acceptedConnectorIds())
                .containsExactlyElementsOf(expected);
    }
}
