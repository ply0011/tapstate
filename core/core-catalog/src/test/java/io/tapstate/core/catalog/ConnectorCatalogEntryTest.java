package io.tapstate.core.catalog;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import io.tapstate.core.model.SourceMode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether a row's modes may be treated as the connector's full matrix. The question is "did a person
 * declare these", not "is this one particular source constant", and the difference only shows up on a
 * connector that is neither a database nor declared upstream.
 */
class ConnectorCatalogEntryTest {

    private static ConnectorCatalogEntry entry(ConnectorGroup group, ModeSource source, SourceMode mode) {
        Map<SourceMode, ModeSource> bySource = new EnumMap<>(SourceMode.class);
        bySource.put(mode, source);
        return new ConnectorCatalogEntry("id", "name", "Name", null, group, List.of(mode),
                Discovery.NONE, new SinkCapability(false, null), false, List.of(),
                new Provenance(null, null, null, null, null, null, bySource));
    }

    @Test
    void anOverlayDeclarationIsTrustedJustLikeAnUpstreamOne() {
        // The whole point of the third source. A non-database connector carrying only an overlay
        // declaration must be trusted, or moving the eighteen declarations into the overlay silently
        // turns off mode validation for every one of them that is not a database — sixteen of them.
        assertThat(entry(ConnectorGroup.MQ, ModeSource.OVERLAY, SourceMode.STREAM).modesAreTrustworthy())
                .isTrue();
    }

    @Test
    void anUpstreamDeclarationIsStillTrusted() {
        assertThat(entry(ConnectorGroup.SAAS, ModeSource.DECLARED, SourceMode.API).modesAreTrustworthy())
                .isTrue();
    }

    @Test
    void aDerivedNonDatabaseIsNotTrusted() {
        // Derivation cannot tell stream/api/file apart from cdc, so a non-database connector with only
        // derived modes carries a guess, and offline must defer rather than reject.
        assertThat(entry(ConnectorGroup.MQ, ModeSource.DERIVED, SourceMode.CDC).modesAreTrustworthy())
                .isFalse();
    }

    @Test
    void aDerivedNonDatabaseCarriesUnverifiedModes() {
        // The row that ships a claim nobody checked: modes are present, so validation admits them,
        // and the only thing behind them is a probe that cannot tell this connector from a database.
        assertThat(entry(ConnectorGroup.FILE, ModeSource.DERIVED, SourceMode.CDC).modesAreUnverified())
                .isTrue();
    }

    @Test
    void aRowWithNoModesAtAllIsNotUnverified() {
        // The disjointness that keeps the two gaps countable apart. Nothing was resolved here, so
        // there is no wrong claim to review - a different gap, with a different fix. Folded together,
        // a row moving from one to the other would leave both totals unchanged.
        assertThat(emptyModed(ConnectorGroup.SAAS).modesAreUnverified()).isFalse();
    }

    @Test
    void aDeclaredRowIsNotUnverified() {
        // Somebody said what it reads, which is the whole exit from this bucket.
        assertThat(entry(ConnectorGroup.MQ, ModeSource.OVERLAY, SourceMode.STREAM).modesAreUnverified())
                .isFalse();
    }

    private static ConnectorCatalogEntry emptyModed(ConnectorGroup group) {
        return new ConnectorCatalogEntry("id", "name", "Name", null, group, List.of(),
                Discovery.NONE, new SinkCapability(false, null), false, List.of(),
                new Provenance(null, null, null, null, null, null, new EnumMap<>(SourceMode.class)));
    }

    @Test
    void aDatabaseIsTrustedEvenWhenOnlyDerived() {
        assertThat(entry(ConnectorGroup.DATABASE, ModeSource.DERIVED, SourceMode.CDC).modesAreTrustworthy())
                .isTrue();
    }
}
