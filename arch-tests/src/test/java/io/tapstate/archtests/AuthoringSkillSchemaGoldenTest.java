package io.tapstate.archtests;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Anti-drift gate for the offline authoring-skill bundle.
 *
 * <p>The authoring skill ships a copy of the tapstate/v1 JSON Schema so a bring-your-own agent can
 * ground on the exact grammar without reaching into this repo. That copy must be a faithful mirror
 * of the one the CLI validates against — never a hand-edited fork. If the canonical schema is
 * regenerated and the bundled copy is not refreshed, the skill would silently teach a stale grammar.
 * This test makes that drift a red build: the two files must be byte-for-byte identical.
 *
 * <p>The canonical schema is read from the classpath (the module that owns it is on this module's
 * test classpath); the bundled copy is read from the repo tree relative to this module.
 */
class AuthoringSkillSchemaGoldenTest {

    /** Canonical, CLI-facing schema, as a classpath resource of the schema-owning module. */
    private static final String CANONICAL_RESOURCE = "/schema/tapstate-v1.schema.json";

    /** Bundled copy shipped with the authoring skill, relative to the repo root. */
    private static final Path BUNDLED_COPY =
            Path.of("authoring-skill", "schema", "tapstate-v1.schema.json");

    @Test
    @DisplayName("the authoring-skill schema copy is byte-identical to the canonical schema")
    void bundledSchemaMirrorsCanonical() {
        byte[] canonical = readCanonical();
        byte[] bundled = readBundled();

        assertThat(bundled)
                .as("authoring-skill/schema/tapstate-v1.schema.json must be a byte-for-byte copy of "
                        + "the canonical tapstate/v1 schema; regenerate the schema and refresh the "
                        + "bundled copy together")
                .isEqualTo(canonical);
    }

    private byte[] readCanonical() {
        try (InputStream in = AuthoringSkillSchemaGoldenTest.class.getResourceAsStream(CANONICAL_RESOURCE)) {
            assertThat(in)
                    .as("canonical schema %s on the test classpath", CANONICAL_RESOURCE)
                    .isNotNull();
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private byte[] readBundled() {
        // Surefire runs with the module directory as the working directory; the bundle lives one
        // level up, at the repo root.
        Path repoRoot = Path.of("").toAbsolutePath().getParent();
        Path copy = repoRoot.resolve(BUNDLED_COPY);
        assertThat(Files.exists(copy))
                .as("bundled authoring-skill schema at %s", copy)
                .isTrue();
        try {
            return Files.readAllBytes(copy);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
