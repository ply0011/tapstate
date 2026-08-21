package io.tapstate.core.dsl;

import io.tapstate.core.model.PipelineResource;
import io.tapstate.core.model.Resource;
import io.tapstate.core.model.ServeBlock;
import io.tapstate.core.model.ServeResource;
import io.tapstate.core.model.SyncElement;
import io.tapstate.core.model.WriteMode;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Judging a batch's writes against the keys their sources were discovered to hold. An upsert matches
 * a write to an existing row by that row's key; a table declaring no key cannot answer which row a
 * write belongs to, so a connector handed one writes rows that are not the ones the pipeline meant.
 * That failure is silent - the write succeeds, the target fills up, and nothing anywhere reports
 * that what it holds is wrong - so it is refused here, before anything runs.
 *
 * <p>Like the row-expression type rules this runs only where a discovered model exists, never in the
 * offline check, which has none, and it judges the tables that actually reach the serve block rather
 * than every table the source was discovered to hold. The key it reads is the one the upstream source
 * declared: the key an upsert matches on comes from the model discovered for the source being read,
 * not from the target being written, and not from the rows in flight.
 *
 * <p>Append is not judged. It never matches a write to an existing row, so it has no use for a key,
 * and a keyless table is an ordinary thing to append to.
 */
public final class WriteKeyRules {

    private final Map<String, List<DiscoveredTable>> tablesBySource;
    private final Map<String, Resource> byId;

    private WriteKeyRules(Collection<Resource> batch, Map<String, List<DiscoveredTable>> tablesBySource) {
        this.tablesBySource = tablesBySource;
        Map<String, Resource> index = new LinkedHashMap<>();
        for (Resource r : batch) {
            index.put(r.id(), r);
        }
        this.byId = index;
    }

    /** Refuses any upsert in {@code batch} that writes a table its source declared no key for. */
    public static void validate(
            Collection<Resource> batch, Map<String, List<DiscoveredTable>> tablesBySource) {
        WriteKeyRules rules = new WriteKeyRules(batch, tablesBySource);
        for (Resource r : batch) {
            if (r instanceof PipelineResource p) {
                rules.validatePipeline(p);
            }
        }
    }

    private void validatePipeline(PipelineResource p) {
        boolean matchesOnAKey = false;
        for (SyncElement sync : syncElements(p.serve())) {
            matchesOnAKey |= matchesOnAKey(sync);
        }
        if (!matchesOnAKey) {
            return;
        }
        for (Upstream up : servedTables(p)) {
            // A table the wiring could not name is not judged. Every other rule here widens to the
            // whole source when it cannot resolve one, which is safe where widening only lets more
            // through - and this rule is the other way round, where widening refuses more. Refusing
            // on a table nobody established the write even reaches would blame the wrong one.
            if (up.table() == null) {
                continue;
            }
            // A source nobody discovered contributes no tables. Saying a table it might hold has
            // no key would be inventing a fact about something never seen; the obligation to
            // discover before applying is a separate rule's to enforce.
            for (DiscoveredTable table : tablesBySource.getOrDefault(up.source(), List.of())) {
                if (table.name().equals(up.table()) && table.primaryKey().isEmpty()) {
                    throw noKey(table.name(), up.source());
                }
            }
        }
    }

    /**
     * The tables the serve block reads, which are the tables the write is made of.
     *
     * <p>Resolved through the same wiring the row-expression rules resolve theirs through, rather
     * than by taking every table the source was discovered to hold. Those two differ by the whole
     * database: a source with no {@code tables:} selector resolves to all of it, and a selector that
     * matches nothing deliberately falls back to all of it as well.
     */
    private Set<Upstream> servedTables(PipelineResource p) {
        Wiring wiring = new Wiring(p, byId);
        return switch (p.serve()) {
            case null -> Set.of();
            case ServeBlock.Inline inline -> wiring.reaching(inline.from());
            case ServeBlock.Use use -> wiring.reaching(use.from());
        };
    }

    /**
     * Whether this element writes by matching rows to keys. The mode is absent far more often than it
     * is spelled out - leaving it out is how the default is written - so an absent mode is the upsert
     * it defaults to, not a mode to skip.
     */
    private static boolean matchesOnAKey(SyncElement sync) {
        return sync.writeMode() == null || sync.writeMode() == WriteMode.UPSERT;
    }

    private static DslException noKey(String table, String source) {
        String path = "serve.sync";
        return new DslException(DslError.UPSERT_NEEDS_KEY, path, 0, 0, null,
                Map.of("table", table, "source", source, "path", path));
    }

    /** The sync elements a serve block declares, inline or through the definition it names. */
    private List<SyncElement> syncElements(ServeBlock serve) {
        List<SyncElement> sync = switch (serve) {
            case null -> null;
            case ServeBlock.Inline inline -> inline.sync();
            case ServeBlock.Use use ->
                    byId.get(use.use()) instanceof ServeResource definition ? definition.sync() : null;
        };
        return sync == null ? List.of() : sync;
    }
}
