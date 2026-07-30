package io.tapstate.archtests;

import io.tapstate.cli.Cli;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import picocli.CommandLine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Smoke gate for every {@code tapstate ...} command line the authoring skill's docs show a user or
 * agent verbatim. The skill's docs are the only channel an agent reads before ever touching the CLI,
 * so a command shown there that the CLI's real argument grammar rejects (wrong flag, positional where
 * an option is required, ...) sends every reader down a dead end. This gate extracts every such
 * command reference — from inline code spans and fenced code blocks alike — straight from the docs
 * tree and feeds it to the real, fully-wired {@link Cli#newCommandLine()} grammar via
 * {@code parseArgs}, which validates argument shape without executing any business logic or touching
 * the filesystem/catalog. A doc command that does not parse fails the build instead of stranding a
 * reader.
 *
 * <p>Commands are discovered from the doc files, never hand-copied into this test — a hand-written
 * list would let a newly-introduced bad example slip through unexercised.
 */
class AuthoringSkillDocCommandsSmokeTest {

    /** Every doc in the bundle that an agent or user reads verbatim. */
    private static final List<Path> DOCS = List.of(
            Path.of("authoring-skill", "SKILL.md"),
            Path.of("authoring-skill", "GENERATING.md"),
            Path.of("authoring-skill", "REFERENCE.md"),
            Path.of("authoring-skill", "README.md"));

    /** An inline code span whose content is itself a command, e.g. `` `tapstate new --kind <kind>` ``. */
    private static final Pattern INLINE_COMMAND = Pattern.compile("`(tapstate\\s+[^`]*)`");

    private static Path repoRoot() {
        // Surefire runs with the module directory as the working directory; the bundle is one level up.
        return Path.of("").toAbsolutePath().getParent();
    }

    /** Every {@code tapstate ...} reference in the doc bundle, tagged with its file:line for failures. */
    static Stream<Arguments> docCommands() {
        List<Arguments> found = new ArrayList<>();
        for (Path doc : DOCS) {
            Path full = repoRoot().resolve(doc);
            List<String> lines;
            try {
                lines = Files.readAllLines(full);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            boolean inFence = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                String trimmed = line.strip();
                if (trimmed.startsWith("```")) {
                    inFence = !inFence;
                    continue;
                }
                String location = doc + ":" + (i + 1);
                if (inFence) {
                    if (trimmed.startsWith("tapstate ") || trimmed.equals("tapstate")) {
                        found.add(Arguments.of(location, stripTrailingComment(trimmed)));
                    }
                } else {
                    Matcher m = INLINE_COMMAND.matcher(line);
                    while (m.find()) {
                        found.add(Arguments.of(location, m.group(1).strip()));
                    }
                }
            }
        }
        return found.stream();
    }

    private static String stripTrailingComment(String line) {
        int hash = line.indexOf('#');
        return (hash >= 0 ? line.substring(0, hash) : line).strip();
    }

    @Test
    @DisplayName("the doc bundle yields at least one tapstate command reference")
    void commandsArePresent() {
        assertThat(docCommands().toList())
                .as("tapstate command references discovered across %s", DOCS)
                .isNotEmpty();
    }

    @ParameterizedTest(name = "{0}: {1}")
    @MethodSource("docCommands")
    @DisplayName("every tapstate command shown in the docs parses against the real CLI grammar")
    void commandParsesAgainstRealCli(String location, String command) {
        String[] tokens = command.split("\\s+");
        assertThat(tokens[0]).as("command at %s starts with the CLI name", location).isEqualTo("tapstate");
        String[] args = Arrays.copyOfRange(tokens, 1, tokens.length);
        CommandLine cli = Cli.newCommandLine();
        assertThatCode(() -> cli.parseArgs(args))
                .as("doc command `%s` at %s must parse against the CLI's actual argument grammar", command, location)
                .doesNotThrowAnyException();
    }
}
