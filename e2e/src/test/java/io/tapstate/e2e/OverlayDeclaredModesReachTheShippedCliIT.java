package io.tapstate.e2e;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A mode this repository declares reaches the person running the packaged CLI.
 *
 * <p>kafka is the case with no derivable signal: nothing about its jar says which modes it reads, so
 * its {@code ["stream"]} exists in exactly one place - the declaration checked in beside the catalog -
 * and reaches a user only if assembly merged it into the shipped catalog resource and the CLI loaded
 * that resource. This drives the whole of that chain from outside, as a user does: a workspace on
 * disk, the CLI as its own process, and the answer read off its exit code and output.
 *
 * <p>What makes it discriminate rather than merely pass: losing the declaration anywhere along the
 * chain does not fail loudly. An entry carrying no modes counts as "no trustworthy offline signal",
 * which admits every mode - so the regression looks like a CLI that stopped refusing, and the only
 * thing that catches it is asserting the refusal still happens. The accepting half is here for the
 * same reason inverted: a CLI that refused everything would satisfy the first assertion while being
 * just as broken.
 */
@DisplayName("a mode this repository declares reaches the packaged CLI")
class OverlayDeclaredModesReachTheShippedCliIT {

    /** Where the build wrote the classpath the CLI is launched from. */
    private static final String CLI_CLASSPATH_PROPERTY = "tapstate.e2e.cli-classpath";

    @Test
    @DisplayName("a mode the declaration does not carry is refused, and the refusal names the ones it does")
    void refusesAModeTheDeclarationDoesNotCarry(@TempDir Path workspace) {
        write(workspace, "cdc");

        CliRun run = runCli("validate", workspace.toString());

        assertThat(run.exitCode()).as("validate must refuse, so it cannot exit 0").isNotZero();
        assertThat(run.stdout() + run.stderr())
                .contains("dsl.unsupported-mode")
                .contains("stream");
    }

    @Test
    @DisplayName("the mode the declaration does carry is accepted")
    void acceptsTheModeTheDeclarationCarries(@TempDir Path workspace) {
        write(workspace, "stream");

        CliRun run = runCli("validate", workspace.toString());

        assertThat(run.exitCode()).as("output was:\n%s%s", run.stdout(), run.stderr()).isZero();
        assertThat(run.stdout()).contains("valid");
    }

    private static void write(Path workspace, String mode) {
        try {
            Files.writeString(workspace.resolve("src.tap.yml"), """
                    version: tapstate/v1
                    kind: source
                    id: overlay_probe
                    connector: kafka
                    mode: %s
                    """.formatted(mode));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What one CLI process produced. */
    private record CliRun(int exitCode, String stdout, String stderr) {
    }

    /** Launches the CLI as its own process on the classpath the build recorded, and waits for it. */
    private static CliRun runCli(String... args) {
        List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", cliClasspath(),
                "io.tapstate.cli.Cli"));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(2, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new AssertionError("the CLI did not exit; output so far:\n" + out + err);
            }
            return new CliRun(process.exitValue(), out, err);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted waiting for the CLI", e);
        }
    }

    private static String cliClasspath() {
        String file = System.getProperty(CLI_CLASSPATH_PROPERTY);
        assertThat(file)
                .as("the build must set %s so the CLI can be launched", CLI_CLASSPATH_PROPERTY)
                .isNotBlank();
        try {
            return Files.readString(Path.of(file)).trim();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
