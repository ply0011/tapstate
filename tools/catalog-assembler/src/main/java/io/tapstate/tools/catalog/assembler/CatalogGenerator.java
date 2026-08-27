package io.tapstate.tools.catalog.assembler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.tapstate.core.catalog.CatalogEntryWriter;
import io.tapstate.core.catalog.ConnectorCatalogEntry;
import io.tapstate.core.catalog.ConnectorOverlay;

/**
 * Composes the whole PDK-free assembly path into the deterministic catalog artifacts: walk a
 * connectors checkout, assemble entries (merging the derived bitmap and declared modes through the
 * core rules), then serialize the index, the per-connector entries and the ingest report. Reads spec
 * files from the checkout; produces content only, leaving writing and byte-locking to the caller.
 */
final class CatalogGenerator {

    private CatalogGenerator() {
    }

    static GeneratedCatalog generate(Path connectorsRoot, String specSha, String capabilitySha,
                                     Map<String, Set<String>> bitmap) {
        WalkResult walk = ConnectorWalker.walk(connectorsRoot);
        // Read once for the whole run: it is the same handful of files every time, and a run that
        // cannot read them must fail before it writes a snapshot rather than midway through one.
        Assembly assembly = CatalogAssembler.assemble(walk, specSha, capabilitySha, bitmap,
                ConnectorOverlay.load(), relativePath -> read(connectorsRoot.resolve(relativePath)));

        JsonWriter writer = new JsonWriter();
        List<String> ids = new ArrayList<>();
        Map<String, String> seenLowercase = new LinkedHashMap<>();
        Map<String, String> entries = new LinkedHashMap<>();
        for (ConnectorCatalogEntry entry : assembly.entries()) {
            // Entry files are <id>.json, which collapse on a case-insensitive filesystem; reject ids
            // that differ only in case so one entry is never silently overwritten by another.
            String prior = seenLowercase.put(entry.id().toLowerCase(java.util.Locale.ROOT), entry.id());
            if (prior != null) {
                throw new IllegalStateException(
                        "connector ids collide case-insensitively: '" + prior + "' and '" + entry.id() + "'");
            }
            ids.add(entry.id());
            entries.put(entry.id(), writer.write(CatalogEntryWriter.toTree(entry)));
        }
        // The index carries the two revisions once for the whole catalog. Per entry they were the
        // same value copied once per connector, so a refresh that changed one connector rewrote every
        // file - and the spec-face refresh opens a pull request daily.
        Map<String, Object> head = new LinkedHashMap<>();
        head.put("specSha", specSha);
        head.put("capabilitySha", capabilitySha);
        head.put("entries", new ArrayList<Object>(ids));
        String index = writer.write(head);
        String report = ReportRenderer.render(assembly.report());
        return new GeneratedCatalog(index, entries, report);
    }

    private static String read(Path file) {
        try {
            return Files.readString(file);
        } catch (IOException e) {
            throw new UncheckedIOException("reading " + file, e);
        }
    }
}
