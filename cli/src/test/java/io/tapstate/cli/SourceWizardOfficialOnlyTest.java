package io.tapstate.cli;

import io.tapstate.core.catalog.OfficialConnectors;
import io.tapstate.core.catalog.TapstateCatalog;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The wizard's connector question offers only what this release can actually register. Offering the
 * whole bundled catalog reads as a menu of choices and is not one: picking anything outside the
 * supported set walks the user all the way through the questions, writes an artifact, and is refused
 * only much later at register time.
 */
class SourceWizardOfficialOnlyTest {

    @Test
    void offersExactlyTheConnectorsThisReleaseSupports() {
        ScriptedPrompter p = new ScriptedPrompter("mysql");

        new SourceWizard(p, TapstateCatalog.load()).run();

        // Set equality rather than "does not offer kafka": the weaker form stays green on a filter
        // that also drops a supported connector, which is the failure a user would actually hit.
        assertThat(p.offered.get(0)).containsExactlyInAnyOrderElementsOf(OfficialConnectors.IDS);
    }

    @Test
    void theCatalogItNarrowsIsGenuinelyWiderThanThatSet() {
        // Keeps the assertion above from passing vacuously. If the bundled catalog ever shrank to the
        // supported set on its own, the filter could be deleted and no test would notice.
        assertThat(TapstateCatalog.load().ids())
                .contains("kafka")
                .hasSizeGreaterThan(OfficialConnectors.IDS.size());
    }
}
