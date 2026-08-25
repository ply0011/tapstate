package io.tapstate.tools.catalog.assembler;

import org.junit.jupiter.api.Test;

import io.tapstate.core.catalog.TapstateCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The list of connectors this repository cannot build is consulted by id, in two places that both
 * fail quietly on a name that matches nothing: the worklist skips ids it holds, and the report
 * attaches its reasons by id. A typo is therefore invisible twice over - the connector is still put
 * on the worklist and still stops the reactor, and the report says nothing about why, which reads
 * as an unexpected drop-out rather than as a list that no longer names anything.
 *
 * <p>So the list is checked against the catalog itself: an id on it that no shipped connector
 * answers to is a stale or misspelled entry, and an id whose reason is blank is a name with no
 * decision behind it.
 */
class UnbuildableConnectorsTest {

    @Test
    void everyNamedConnectorIsOneTheCatalogActuallyShips() {
        assertThat(TapstateCatalog.load().ids()).containsAll(UnbuildableConnectors.ids());
    }

    @Test
    void everyNamedConnectorCarriesAReason() {
        assertThat(UnbuildableConnectors.ids())
                .isNotEmpty()
                .allSatisfy(id -> assertThat(UnbuildableConnectors.reasonFor(id)).isNotBlank());
    }
}
