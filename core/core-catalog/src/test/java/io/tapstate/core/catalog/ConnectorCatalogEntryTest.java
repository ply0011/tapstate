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
                new Provenance(null, null, null, null, null, bySource));
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
    void aDatabaseIsTrustedEvenWhenOnlyDerived() {
        assertThat(entry(ConnectorGroup.DATABASE, ModeSource.DERIVED, SourceMode.CDC).modesAreTrustworthy())
                .isTrue();
    }
}
