package io.tapstate.tools.catalog.assembler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * Resolves the connectors checkout a refresh reads, either from the path it was handed or by walking
 * up from the working directory to a sibling named {@code tapdata-connectors}.
 *
 * <p>The walk is the older of the two and stays the default, because it is what a checkout laid out
 * beside this repository gets for free. What it cannot do is say <em>which</em> checkout: it takes
 * the first one above the working directory, and a machine that has run this pipeline before tends
 * to have several. The catalog is stamped with the revision of whatever is read, so the wrong one
 * yields a complete, plausible, wrong catalog that no later step can tell from a right one - which
 * is why a named path is honoured exactly and a named path that is not a checkout ends the run
 * instead of falling back to the walk.
 */
final class ConnectorsCheckout {

    /** Names the checkout to read, overriding the walk. */
    static final String PROPERTY = "tapstate.catalog.connectors";

    private ConnectorsCheckout() {
    }

    /** The checkout named by {@code named}, else the first one above {@code from}. */
    static Optional<Path> locate(String named, Path from) {
        if (named != null && !named.isBlank()) {
            Path path = Path.of(named.trim()).toAbsolutePath().normalize();
            if (!Files.isDirectory(path.resolve("connectors"))) {
                throw new IllegalStateException("-D" + PROPERTY + "=" + named
                        + " is not a connectors checkout: no connectors/ directory under " + path);
            }
            return Optional.of(path);
        }
        for (Path dir = from; dir != null; dir = dir.getParent()) {
            Path candidate = dir.resolve("tapdata-connectors");
            if (Files.isDirectory(candidate.resolve("connectors"))) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }
}
