package io.tapstate.spi.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/**
 * How a field a read names is read: which dots step into a document, and which are part of a name.
 *
 * <p>This exists because the two are not distinguishable in a bare spelling and a column may genuinely
 * hold one. A source is free to name a column {@code price.usd}; that name survives into the stored
 * document as a top-level key, and asking for it the obvious way asks for a nested field instead — a
 * request that matches nothing and reports nothing wrong.
 *
 * <p>It is parsed in one place because it is consumed in two: in memory against a row already held, and
 * translated into a backend's own dialect. Two parses of one spelling is two answers to one question, and
 * the shape that failure takes is a filter meaning different things in different views.
 */
class FieldPathTest {

    @Test
    void readsABareDotAsAStepIntoADocument() {
        assertThat(FieldPath.of("shipping.address.city").segments())
                .containsExactly("shipping", "address", "city");
    }

    @Test
    void readsAnEscapedDotAsPartOfTheName() {
        // The whole point: one segment named `price.usd`, not a `usd` inside a `price`.
        assertThat(FieldPath.of("price\\.usd").segments()).containsExactly("price.usd");
    }

    @Test
    void readsBothKindsOfDotInOneSpelling() {
        // A column whose name holds a dot, inside a document. Neither reading alone covers this.
        assertThat(FieldPath.of("totals.price\\.usd").segments())
                .containsExactly("totals", "price.usd");
    }

    @Test
    void readsAnEscapedBackslashAsPartOfTheName() {
        // Without this the escape has no way to spell itself, and a column actually named `a\b` would be
        // unreachable while `a\.b` would be ambiguous between two readings.
        assertThat(FieldPath.of("a\\\\b").segments()).containsExactly("a\\b");
    }

    @Test
    void aPlainPathIsTheOneThatCanTravelAsItWasWritten() {
        // A backend whose query language spells paths with dots can be handed this spelling directly. One
        // holding a literal dot cannot, and asks for a translation that costs more -- so the difference is
        // worth naming rather than rediscovering at each call site.
        assertThat(FieldPath.of("shipping.address").isPlainPath()).isTrue();
        assertThat(FieldPath.of("price\\.usd").isPlainPath()).isFalse();
        assertThat(FieldPath.of("totals.price\\.usd").isPlainPath()).isFalse();
    }

    @Test
    void givesBackTheSpellingItWasReadFrom() {
        // Kept because a refusal has to name the field the caller wrote, not a normalised form of it: a
        // reader told about `price.usd` when they wrote `price\.usd` has been told about a different field.
        assertThat(FieldPath.of("shipping.address.city").asWritten()).isEqualTo("shipping.address.city");
        assertThat(FieldPath.of("price\\.usd").asWritten()).isEqualTo("price\\.usd");
    }

    @Test
    void refusesAnEscapeThatEscapesNothing() {
        // Silently keeping or silently dropping a stray backslash both invent a field name the caller did
        // not write, and the read that follows reports nothing wrong.
        assertThatThrownBy(() -> FieldPath.of("price\\usd"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("escape");
    }

    @Test
    void refusesASpellingThatEndsMidEscape() {
        assertThatThrownBy(() -> FieldPath.of("price\\"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ends");
    }

    @Test
    void refusesAnEmptySegment() {
        // `a..b` and a leading or trailing dot name a field with no name. Left alone they reach the
        // backend as a path with a hole in it, which matches nothing.
        assertThatThrownBy(() -> FieldPath.of("a..b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FieldPath.of(".a")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FieldPath.of("a.")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FieldPath.of("")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refusesNothingAtAll() {
        assertThatThrownBy(() -> FieldPath.of(null)).isInstanceOf(NullPointerException.class);
    }
}
