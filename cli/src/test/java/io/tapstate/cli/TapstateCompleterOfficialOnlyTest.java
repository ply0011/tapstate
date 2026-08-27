package io.tapstate.cli;

import io.tapstate.core.catalog.OfficialConnectors;
import io.tapstate.core.schema.SchemaNavigator;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tab completion after {@code --connector} offers only what this release can register. Kept apart
 * from the wizard's own test on purpose: the two read the catalog from separate code, so narrowing
 * one and not the other has to fail somewhere, and this is the half that would otherwise stay green.
 */
class TapstateCompleterOfficialOnlyTest {

    private final TapstateCompleter completer =
            TapstateCompleter.forRepl(Cli.newCommandLine(), SchemaNavigator.bundled());

    @Test
    void completesOnlyTheConnectorsThisReleaseSupports() {
        // Set equality rather than "does not offer kafka" — the weaker form also passes on a filter
        // that drops a supported connector.
        assertThat(completer.candidates(List.of("new", "--connector", ""), 2))
                .containsExactlyInAnyOrderElementsOf(OfficialConnectors.IDS);
        assertThat(completer.candidates(List.of("new", "-c", ""), 2))
                .containsExactlyInAnyOrderElementsOf(OfficialConnectors.IDS);
    }

    @Test
    void aPrefixStillNarrowsWithinThatSet() {
        List<String> narrowed = completer.candidates(List.of("new", "-c", "my"), 2);
        // Two of the supported ids start with this prefix and both are official, so narrowing means
        // fewer than the whole set — not one result. Asserting a single id here would silently start
        // failing the moment a second supported connector shares a prefix, which says nothing about
        // the filter being right.
        assertThat(narrowed).containsExactly("mysql", "mysql-pxc");
        assertThat(narrowed).hasSizeLessThan(OfficialConnectors.IDS.size());
    }

    @Test
    void anUnsupportedConnectorIsNotReachableByPrefixEither() {
        // The bundled catalog carries kafka; completion must not be the thing that suggests it.
        assertThat(completer.candidates(List.of("new", "-c", "kaf"), 2)).isEmpty();
    }
}
