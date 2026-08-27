package io.tapstate.core.catalog;

import java.util.Arrays;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** The three places a mode can come from, and which of them count as somebody having declared it. */
class ModeSourceTest {

    @Test
    void onlyDerivationIsAGuess() {
        assertThat(ModeSource.DERIVED.isDeclaration()).isFalse();
        assertThat(ModeSource.DECLARED.isDeclaration()).isTrue();
        assertThat(ModeSource.OVERLAY.isDeclaration()).isTrue();
    }

    @Test
    void everySourceIsAccountedForHere() {
        // Pins the count so that adding a fourth source fails here rather than quietly inheriting
        // whichever answer the callers' comparisons happen to imply. Whoever adds it has to come here
        // and say whether it is a declaration.
        assertThat(ModeSource.values()).hasSize(3);
    }

    @Test
    void eachSourceSerialisesUnderItsOwnName() {
        // The yaml value is what a catalog row carries and what a reader parses back, so two sources
        // sharing one name would silently merge on the round trip.
        assertThat(Arrays.stream(ModeSource.values()).map(ModeSource::yaml).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("derived", "declared", "overlay");
    }
}
