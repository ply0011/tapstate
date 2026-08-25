package io.tapstate.tools.catalog.derive;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The refresh-job invocation of catalog-derive: given the manifest the assembler emitted, the
 * connectors dist directory and an output path (all as system properties), it classloads and probes
 * every connector for real and writes the bitmap. Gated on the properties, so it runs only as the
 * deliberate derive step of a refresh and skips during normal builds (which have no connector jars).
 *
 * <p>Beside the bitmap it writes {@code <out>.skipped} — the connectors that produced no capability
 * bits and why. Those are the entries whose modes can only come from a declaration, so they are the
 * working list a refresh hands back, and they appear nowhere else: the bitmap holds what was derived,
 * and a catalog diff shows what changed rather than what never arrived. The file is written even when
 * nothing was skipped, so its absence means the step did not run rather than that it had nothing to
 * report — the two are otherwise the same empty answer.
 */
class CatalogDeriveRealRunTest {

    @Test
    void derivesTheBitmapFromTheRealCheckoutWhenAskedTo() throws IOException {
        String manifest = System.getProperty("tapstate.derive.manifest");
        String dist = System.getProperty("tapstate.derive.dist");
        String out = System.getProperty("tapstate.derive.out");
        assumeTrue(manifest != null && dist != null && out != null,
                "no -Dtapstate.derive.{manifest,dist,out} — not a real derive run, skipping");

        EmitOutcome outcome =
                CatalogDerive.run(Path.of(manifest), Path.of(dist), Path.of(out), ConnectorCapabilityProbe::probe);

        Map<String, String> skipped = new TreeMap<>(outcome.skipped());
        Files.writeString(Path.of(out + ".skipped"), skipped.entrySet().stream()
                .map(entry -> entry.getKey() + "\t" + entry.getValue() + "\n")
                .collect(Collectors.joining()));
        System.out.printf("derived %d of %d connectors; %d skipped%n",
                outcome.bitmap().size(), outcome.bitmap().size() + skipped.size(), skipped.size());
        skipped.forEach((id, reason) -> System.out.printf("  skipped %s — %s%n", id, reason));
    }
}
