package io.tapstate.tools.catalog.assembler;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Which connectors checkout a refresh reads. The catalog is stamped with the revision of whatever
 * this resolves, so picking the wrong one produces a complete, plausible, wrong catalog - there is no
 * later step that would notice.
 */
class ConnectorsCheckoutTest {

    @Test
    void findsTheCheckoutSittingAboveTheWorkingDirectory(@TempDir Path tmp) throws IOException {
        Path checkout = checkoutAt(tmp.resolve("tapdata-connectors"));
        Path module = Files.createDirectories(tmp.resolve("tools").resolve("catalog-assembler"));

        assertThat(ConnectorsCheckout.locate(null, module)).contains(checkout);
    }

    @Test
    void findsNothingWhenNoCheckoutSitsAbove(@TempDir Path tmp) throws IOException {
        Path module = Files.createDirectories(tmp.resolve("tools").resolve("catalog-assembler"));

        assertThat(ConnectorsCheckout.locate(null, module)).isEmpty();
    }

    @Test
    void aNamedCheckoutWinsOverTheOneLyingAround(@TempDir Path tmp) throws IOException {
        // The walk finds any directory called tapdata-connectors above the working directory, and a
        // developer machine collects them: a shallow clone from a nightly run, an old worktree. The
        // refresh has to read the one it was pointed at, or it stamps a revision nobody asked for.
        Path lyingAround = checkoutAt(tmp.resolve("tapdata-connectors"));
        Path asked = checkoutAt(tmp.resolve("elsewhere").resolve("tapdata-connectors"));
        Path module = Files.createDirectories(tmp.resolve("tools").resolve("catalog-assembler"));

        Optional<Path> located = ConnectorsCheckout.locate(asked.toString(), module);

        assertThat(located).contains(asked);
        assertThat(located.orElseThrow()).isNotEqualTo(lyingAround);
    }

    @Test
    void aNamedPathThatIsNotACheckoutIsRefusedRatherThanFallenBackFrom(@TempDir Path tmp) throws IOException {
        // Falling back to the walk here is the same failure as above wearing a different hat: the run
        // succeeds, reads a checkout nobody named, and reports nothing. A typo in the path has to be
        // the end of the run.
        checkoutAt(tmp.resolve("tapdata-connectors"));
        Path module = Files.createDirectories(tmp.resolve("tools").resolve("catalog-assembler"));
        Path typo = tmp.resolve("tapdata-conenctors");

        assertThatThrownBy(() -> ConnectorsCheckout.locate(typo.toString(), module))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tapstate.catalog.connectors")
                .hasMessageContaining(typo.toString());
    }

    @Test
    void aBlankPropertyIsNoPropertyAtAll(@TempDir Path tmp) throws IOException {
        // -Dtapstate.catalog.connectors= with nothing after it is what a shell variable that failed to
        // expand looks like. Reading it as "a checkout named the empty string" would refuse a run the
        // walk could have served.
        Path checkout = checkoutAt(tmp.resolve("tapdata-connectors"));
        Path module = Files.createDirectories(tmp.resolve("tools").resolve("catalog-assembler"));

        assertThat(ConnectorsCheckout.locate("  ", module)).contains(checkout);
    }

    private static Path checkoutAt(Path root) throws IOException {
        Files.createDirectories(root.resolve("connectors"));
        return root;
    }
}
