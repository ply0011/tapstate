package io.tapstate.archtests;

import io.tapstate.core.dsl.Workspace;
import io.tapstate.core.dsl.WorkspaceLoader;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end gate for the authoring skill's example corpus.
 *
 * <p>The skill's whole promise is that an agent following it produces .tap.yml that passes
 * {@code tapstate validate}. The bundled examples are the worked shapes an author copies and adapts,
 * so if one of them is invalid the skill ships a lie. This gate runs the exact offline stack the CLI
 * runs — parse, structural strictness, reference closure, connector capability matrix — over every
 * bundled example workspace, so a broken example is a red build rather than a surprise in the user's
 * hands.
 *
 * <p>Every example directory is discovered from the tree, never named here: a hand-written list would
 * let a new example be added and never exercised. The discovery itself is asserted non-empty, so an
 * accidental move of the corpus cannot turn this gate into a silent pass over nothing.
 */
class AuthoringSkillExamplesValidateTest {

    /** The bundled example corpus, relative to the repo root. Each child is one workspace. */
    private static final Path EXAMPLES = Path.of("authoring-skill", "examples");

    private static Path repoRoot() {
        // Surefire runs with the module directory as the working directory; the bundle is one level up.
        return Path.of("").toAbsolutePath().getParent();
    }

    /** Each immediate subdirectory of the corpus is one self-contained example workspace. */
    static Stream<Path> exampleWorkspaces() {
        Path root = repoRoot().resolve(EXAMPLES);
        try (Stream<Path> tree = Files.list(root)) {
            return tree.filter(Files::isDirectory).sorted().toList().stream();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("the example corpus is discovered and non-empty")
    void corpusIsPresent() {
        List<Path> workspaces = exampleWorkspaces().toList();
        assertThat(workspaces)
                .as("bundled example workspaces under %s", EXAMPLES)
                .isNotEmpty();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("exampleWorkspaces")
    @DisplayName("every bundled example workspace passes validate")
    void exampleValidates(Path workspace) {
        Workspace loaded = WorkspaceLoader.load(workspace);
        assertThat(loaded.resources())
                .as("validated resources in example workspace %s", workspace.getFileName())
                .isNotEmpty();
    }
}
