package io.tapstate.core.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Where the two sets of catalog resources sit relative to each other.
 *
 * <p>Both are read by path arithmetic rather than by scanning: an entry is {@code catalog/<id>.json}
 * and a declaration is {@code catalog/overlay/<type>/<id>.json}, and the same connector id appears in
 * both. Only the prefix keeps the two apart, so it is the prefix that is asserted here — laid out
 * flat, a declaration would land on the entry file of the same name, and a build packages one file
 * per path.
 *
 * <p>The other half is that nothing else is packaged under {@code catalog/} at all. A file dropped in
 * there is read by nobody and reported by nothing: the entry loader walks its index rather than the
 * directory, so an unindexed neighbour is simply never opened, and the connector whose declaration
 * went astray to get there goes back to deriving its modes with every other gate still green.
 *
 * <p>One assertion, not two. The disjointness of the two path sets reads like the thing to state
 * outright, but nothing can be written that makes such a statement false: the prefixes differ by
 * construction, and every way of breaking the layout that was tried here takes a loader down before
 * any comparison happens. A line that cannot go red is worse than no line, so what is asserted is the
 * one comparison that does — the packaged files against what the two loaders between them ask for.
 */
class OverlayResourceLayoutTest {

    @Test
    void nothingIsPackagedUnderTheCatalogPrefixThatNeitherLoaderReads() throws Exception {
        Set<String> expected = new TreeSet<>(entryPaths());
        expected.addAll(overlayPaths());

        assertThat(packagedPaths())
                .as("every file under catalog/ is either an entry or a declaration, and is read as one")
                .isEqualTo(expected);
    }

    /** Exactly the paths {@link TapstateCatalog} resolves, rebuilt the way it resolves them. */
    private static Set<String> entryPaths() {
        Set<String> paths = new TreeSet<>();
        paths.add("/catalog/index.json");
        for (String id : TapstateCatalog.load().ids()) {
            paths.add("/catalog/" + id + ".json");
        }
        return paths;
    }

    /**
     * Exactly the paths {@link ConnectorOverlay} asks for, recorded as it asks. Its resource lookup is
     * the seam, so this needs no second copy of its path arithmetic and moves when that moves.
     */
    private static Set<String> overlayPaths() {
        Set<String> asked = new TreeSet<>();
        ConnectorOverlay.read(path -> {
            asked.add(path);
            return resource(path);
        });
        return asked;
    }

    /** Every file actually packaged under the {@code catalog/} prefix. */
    private static Set<String> packagedPaths() throws Exception {
        URL root = OverlayResourceLayoutTest.class.getResource("/catalog");
        assertThat(root).as("the catalog resources are not on the test classpath").isNotNull();
        Path dir = Path.of(root.toURI());
        try (Stream<Path> tree = Files.walk(dir)) {
            Set<String> paths = new TreeSet<>();
            tree.filter(Files::isRegularFile)
                    .map(file -> "/catalog/" + dir.relativize(file).toString().replace('\\', '/'))
                    .forEach(paths::add);
            return paths;
        }
    }

    private static String resource(String path) {
        try (InputStream in = OverlayResourceLayoutTest.class.getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException("reading " + path, e);
        }
    }
}
