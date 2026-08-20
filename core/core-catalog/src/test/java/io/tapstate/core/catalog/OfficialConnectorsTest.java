package io.tapstate.core.catalog;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The release's officially supported connector set. Pinned exactly, because a silent addition here is
 * a support promise nobody made, and both the register path and the authoring surfaces read this one
 * list to decide what they offer and accept.
 */
class OfficialConnectorsTest {

    @Test
    void pinsTheConnectorsThisReleaseSupports() {
        assertThat(OfficialConnectors.IDS).containsExactly("mysql", "mongodb");
    }

    @Test
    void whatACatalogCarriesKeepsThisListsOrder() {
        // A menu and the refusal that names the same connectors should read the same way round, so
        // the order comes from here rather than from however the catalog happens to be sorted.
        assertThat(OfficialConnectors.presentIn(TapstateCatalog.load()))
                .containsExactlyElementsOf(OfficialConnectors.IDS);
    }

    @Test
    void membershipIsAskedOfTheSameList() {
        assertThat(OfficialConnectors.isOfficial("mysql")).isTrue();
        assertThat(OfficialConnectors.isOfficial("kafka")).isFalse();
    }
}
