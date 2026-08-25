package io.tapstate.core.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import io.tapstate.core.model.WriteMode;

/**
 * This repository's own mode declarations for the connectors it ships — the authoritative source for
 * those, overriding both the connector's upstream spec and anything derived from its capabilities.
 *
 * <p>Connectors nobody here ships are untouched: they declare their modes in their own spec, and an
 * id absent from this overlay simply resolves the way it always did. What lives here is the set this
 * repository releases, which is reviewed connector by connector anyway.
 *
 * <p>Laid out one directory per connector type, one file per connector inside it, under the
 * {@code catalog/} resource prefix. The prefix is not cosmetic: the native image embeds resources by
 * a glob rooted there, so a file outside it is present on the JVM and silently absent from the
 * shipped binary — a difference no test running on the JVM can see. One file per connector rather
 * than one file per type so that changing a single connector's declaration diffs a single file.
 *
 * <p>Every way of being malformed throws. That is the point rather than an oversight: falling back to
 * derivation when the overlay cannot be read reproduces, byte for byte, the symptom of the
 * declarations having been wiped — which is the failure this whole mechanism exists to surface. The
 * throw is bare because this is an invariant of our own packaging, not a user-facing condition; the
 * catalog index next door fails the same way for the same reason.
 */
public final class ConnectorOverlay {

    /**
     * The connector types carrying an overlay, one resource directory each. Adding a type is adding a
     * directory and a line here — never a schema change, and never a change at the merge point, which
     * does not need to know the type: whichever file an entry came from already tells you.
     */
    static final List<String> TYPES = List.of("pdk");

    private final Map<String, List<String>> modesById;
    private final Map<String, SinkCapability> sinkById;

    private ConnectorOverlay(Map<String, List<String>> modesById,
                             Map<String, SinkCapability> sinkById) {
        this.modesById = Collections.unmodifiableMap(modesById);
        this.sinkById = Collections.unmodifiableMap(sinkById);
    }

    /** Reads the overlay bundled with this artifact. */
    public static ConnectorOverlay load() {
        return read(ConnectorOverlay::resource);
    }

    /**
     * Reads an overlay from {@code resource}, which answers a resource path with its content or
     * {@code null} when there is no such resource.
     *
     * <p>The only way to build one, {@link #load()} included, so every overlay in existence has been
     * through the same refusals. A second factory taking already-parsed modes would be able to
     * produce an overlay that {@code load()} would have rejected — most usefully in a test, which is
     * precisely where that divergence would go unnoticed.
     */
    public static ConnectorOverlay read(Function<String, String> resource) {
        Map<String, List<String>> modesById = new LinkedHashMap<>();
        Map<String, SinkCapability> sinkById = new LinkedHashMap<>();
        for (String type : TYPES) {
            String dir = "/catalog/overlay/" + type + "/";
            readType(resource, dir, modesById, sinkById);
        }
        return new ConnectorOverlay(modesById, sinkById);
    }

    private static void readType(Function<String, String> resource, String dir,
                                 Map<String, List<String>> into,
                                 Map<String, SinkCapability> sinkInto) {
        String indexPath = dir + "index.json";
        String indexJson = resource.apply(indexPath);
        if (indexJson == null) {
            throw new IllegalStateException("connector overlay index missing: " + indexPath);
        }
        if (!(CatalogJson.parse(indexJson) instanceof List<?> ids)) {
            throw new IllegalStateException("connector overlay index is not a JSON array: " + indexPath);
        }
        for (Object idValue : ids) {
            String id = String.valueOf(idValue);
            if (into.containsKey(id)) {
                throw new IllegalStateException("duplicate connector id in overlay: " + id);
            }
            String entryPath = dir + id + ".json";
            String entryJson = resource.apply(entryPath);
            if (entryJson == null) {
                throw new IllegalStateException(
                        "connector overlay entry missing: " + entryPath + " (indexed as '" + id + "')");
            }
            if (!(CatalogJson.parse(entryJson) instanceof Map<?, ?> entry)) {
                throw new IllegalStateException(
                        "connector overlay entry is not a JSON object: " + entryPath);
            }
            into.put(id, modesOf(id, entryPath, entry));
            SinkCapability sink = sinkOf(id, entryPath, entry);
            if (sink != null) {
                sinkInto.put(id, sink);
            }
        }
    }

    private static List<String> modesOf(String id, String entryPath, Map<?, ?> entry) {
        if (!(entry.get("modes") instanceof List<?> declared) || declared.isEmpty()) {
            // An empty list is refused as hard as a missing one, and deliberately. It would resolve to
            // zero modes, zero modes makes the mode check return early, and that connector's mode
            // validation would then be off with every gate still green. A connector that genuinely
            // supports nothing does not belong in the overlay at all.
            throw new IllegalStateException(
                    "connector overlay entry '" + id + "' declares no modes: " + entryPath
                            + " - a connector with no modes to declare does not belong in the overlay");
        }
        List<String> modes = new ArrayList<>();
        for (Object mode : declared) {
            modes.add(String.valueOf(mode));
        }
        return Collections.unmodifiableList(modes);
    }

    /** The modes this repository declares for {@code connectorId}, or {@code null} if it declares none. */
    public List<String> modesFor(String connectorId) {
        return modesById.get(connectorId);
    }

    /**
     * The sink block, or {@code null} when the entry declares none - which means "derive it", not
     * "there is no sink". Every entry written before the block existed declares modes only, and
     * reading those as sink-less would turn eighteen connectors into non-targets in one step.
     *
     * <p>Declarable at all because sink capability is read off the {@code write_record} capability,
     * which needs the connector's jar: for a connector this repository cannot build, a declaration is
     * the only honest source there is.
     */
    private static SinkCapability sinkOf(String id, String entryPath, Map<?, ?> entry) {
        Object declared = entry.get("sink");
        if (declared == null) {
            return null;
        }
        if (!(declared instanceof Map<?, ?> sink)) {
            throw new IllegalStateException(
                    "connector overlay entry '" + id + "' has a sink that is not an object: " + entryPath);
        }
        if (!Boolean.TRUE.equals(sink.get("capable"))) {
            // Refused for the same reason an empty modes list is: a present-but-false sink and an
            // absent one behave identically downstream, so the one that declares nothing has to be
            // the absent block. A connector that genuinely cannot sink declares no sink.
            throw new IllegalStateException(
                    "connector overlay entry '" + id + "' declares a sink that is not capable: "
                            + entryPath + " - a connector that cannot sink declares no sink block");
        }
        // Refused for the same reason a false capable flag is: a capable sink with no write
        // semantics is a shape derivation can never produce - SinkRules always yields at least
        // append once write_record is present - so it would ship as fact while matching nothing any
        // other path could have written. A non-list here is the ordinary slip of declaring one
        // semantic without the brackets, and reading it leniently drops it without a word.
        if (!(sink.get("writeSemantics") instanceof List<?> declaredSemantics) || declaredSemantics.isEmpty()) {
            throw new IllegalStateException(
                    "connector overlay entry '" + id + "' declares a sink with no writeSemantics array: "
                            + entryPath + " - a capable sink names at least one write semantic");
        }
        List<WriteMode> semantics = new ArrayList<>();
        for (Object mode : declaredSemantics) {
            String token = String.valueOf(mode);
            try {
                semantics.add(WriteMode.valueOf(token.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException unknown) {
                // Named with the file to open, like every other refusal here: the bare valueOf
                // message carries the token alone, which says nothing about which entry wrote it.
                throw new IllegalStateException(
                        "connector overlay entry '" + id + "' declares an unknown write semantic '"
                                + token + "': " + entryPath, unknown);
            }
        }
        return new SinkCapability(true, semantics);
    }

    /** The sink this repository declares for {@code connectorId}, or {@code null} if it declares none. */
    public SinkCapability sinkFor(String connectorId) {
        return sinkById.get(connectorId);
    }

    /** Every id this overlay declares. */
    public Set<String> ids() {
        return modesById.keySet();
    }

    private static String resource(String path) {
        try (InputStream in = ConnectorOverlay.class.getResourceAsStream(path)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("reading connector overlay resource " + path, e);
        }
    }
}
