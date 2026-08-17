package io.tapstate.messages;

import io.tapstate.core.common.TapstateErrorCode;
import io.tapstate.core.common.Severity;
import io.tapstate.core.dsl.DslError;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The error-code message catalog: the presentation-layer renderer that turns a coded exception into
 * a user-facing diagnostic via the bundled {@code messages/en.yml} catalog, substituting named
 * placeholders from the exception's arguments and falling back to the bare canonical code.
 */
class MessageCatalogTest {

    private static final MessageCatalog EN = MessageCatalog.bundled();

    @Test
    void rendersNamedPlaceholdersFromTheArgs() {
        MessageCatalog.Rendered r = EN.render(DslError.UNKNOWN_FIELD,
                Map.of("field", "srcc", "path", "source"));
        assertThat(r.message()).contains("srcc").contains("source").doesNotContain("{");
    }

    @Test
    void carriesASolutionHint() {
        MessageCatalog.Rendered r = EN.render(DslError.UNKNOWN_FIELD,
                Map.of("field", "srcc", "path", "source"));
        assertThat(r.solution()).isNotNull().isNotEmpty();
    }

    @Test
    void theRefusalAboutADottedNameCarriesTheSpellingThatAddressesIt() {
        // The one refusal that names a field the reader cannot address the obvious way, and so the one
        // whose hint has to do more than point elsewhere: saying it "can still be filtered on" and
        // stopping is a dead end, because the spelling that does it appears nowhere a reader would look.
        // Both forms, because which one they need depends on whether they are typing it or sending it.
        // Named by its literal code: this module cannot see the enum that declares it, and the golden
        // in arch-tests pins the string, so it cannot drift here unnoticed.
        MessageCatalog.Rendered r = EN.render("data-browser.unorderable-field",
                Map.of("field", "price\\.usd"));

        assertThat(r.solution())
                .as("the spelling as it is typed into the shell")
                .contains("price\\.usd");
        assertThat(r.solution())
                .as("and as it survives JSON, where a lone backslash is not an escape the format allows")
                .contains("\"price\\\\.usd\"");
    }

    @Test
    void unknownCodeFallsBackToTheBareCanonicalCode() {
        TapstateErrorCode absent = new TapstateErrorCode() {
            @Override
            public String code() {
                return "cli.not-in-catalog-test";
            }

            @Override
            public Severity severity() {
                return Severity.ERROR;
            }

            @Override
            public Set<String> placeholders() {
                return Set.of();
            }
        };
        MessageCatalog.Rendered r = EN.render(absent, Map.of());
        assertThat(r.message()).isEqualTo("cli.not-in-catalog-test");
        assertThat(r.solution()).isNull();
    }

    @Test
    void everyDslCodeRendersFullyWithNoLeftoverPlaceholders() {
        for (DslError code : DslError.values()) {
            Map<String, Object> args = new HashMap<>();
            for (String placeholder : code.placeholders()) {
                args.put(placeholder, "X");
            }
            MessageCatalog.Rendered r = EN.render(code, args);
            assertThat(r.message())
                    .as("message for %s must be present in the catalog and fully substituted", code.code())
                    .isNotEqualTo(code.code())
                    .doesNotContain("{")
                    .doesNotContain("}");
        }
    }

    @Test
    void readerKeepsColonsInsideQuotedValues() {
        Map<String, MessageCatalog.Entry> parsed = MessageCatalog.parse("x.y:\n  message: \"a: b {z}\"\n");
        assertThat(parsed.get("x.y").message()).isEqualTo("a: b {z}");
        assertThat(parsed.get("x.y").solution()).isNull();
    }
}
