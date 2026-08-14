package io.tapstate.app;

import static org.assertj.core.api.Assertions.assertThat;

import com.hazelcast.function.SupplierEx;
import io.tapstate.core.model.Embed;
import io.tapstate.core.model.EmbedAs;
import io.tapstate.core.model.FromClause;
import io.tapstate.core.model.FromRef;
import io.tapstate.core.model.NestRoot;
import io.tapstate.core.model.PipelineResource;
import io.tapstate.core.model.ReadMode;
import io.tapstate.core.model.Settings;
import io.tapstate.core.model.SourceMode;
import io.tapstate.core.model.SourceResource;
import io.tapstate.core.model.Step;
import io.tapstate.core.model.TableRef;
import io.tapstate.core.model.TransformBody;
import io.tapstate.core.model.ViewBlock;
import io.tapstate.spi.sink.DdlPolicy;
import io.tapstate.spi.sink.SinkWriter;
import io.tapstate.spi.sink.TargetField;
import io.tapstate.spi.sink.TargetTable;
import io.tapstate.spi.sink.WriteMode;
import io.tapstate.spi.store.DiscoveredSourceModel;
import io.tapstate.spi.store.SourceField;
import io.tapstate.spi.store.SourceModel;
import io.tapstate.spi.store.SourceTable;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * A nest's assembled documents materialize into a declared view carrying the root's key, so a
 * re-sent document converges on the one it replaces instead of accumulating beside it.
 *
 * <p>The serve path already resolves this: a nest emits under the id of the step that assembled it,
 * and the write-side resolution registers that step id against the root's model so an upsert has a
 * key to match on. A view reaches its target down a different path - the store is the deployment's
 * rather than a source the author named, so the collection is resolved on the view's behalf - and
 * that path answers every stream with the same table descriptor built from the view alone.
 *
 * <p>The discriminating assertion is the key rather than the collection name. A view whose documents
 * land in the right collection with no key at all satisfies "the assembled documents are
 * materialized" while leaving every update to insert a second document beside the first - the
 * failure is invisible in the collection's name, in the document count of a snapshot-only run, and
 * in any topology assertion, and only shows up once the same root is sent twice.
 */
class AnAssembledDocumentMaterializesIntoTheViewWithTheRootsKeyTest {

    private static final String PARENT_SOURCE = "src_orders";
    private static final String CHILD_SOURCE = "src_items";
    private static final String PARENT_TABLE = "orders";
    private static final String CHILD_TABLE = "order_items";
    private static final String STEP = "order_doc";
    private static final String PIPELINE = "nested_orders";
    private static final String VIEW = "order_state";
    private static final String EMBED_PATH = "items";

    @Test
    void theViewSinkIsToldWhatKeyTheAssembledDocumentsConvergeOn() {
        AtomicReference<Map<String, TargetTable>> bound = new AtomicReference<>();
        new StoreBackedDagSource(seedStore(), capturing(bound)).dagFor(PIPELINE);

        Map<String, TargetTable> targets = bound.get();
        assertThat(targets)
                .as("the streams the view sink was given a model for; the nest emits under '%s'", STEP)
                .containsKey(STEP);

        TargetTable assembled = targets.get(STEP);
        assertThat(assembled.name())
                .as("the collection assembled documents materialize into, which the view names")
                .isEqualTo(VIEW);
        assertThat(assembled.fields().stream().filter(TargetField::primaryKey).map(TargetField::name))
                .as("the key an upsert matches a re-sent assembled document on")
                .containsExactly("id");
    }

    /** Records the map handed to the sink binder without building a writer. */
    private static StoreBackedDagSource.SinkWriterBinder capturing(
            AtomicReference<Map<String, TargetTable>> bound) {
        return new StoreBackedDagSource.SinkWriterBinder() {

            @Override
            public SupplierEx<? extends SinkWriter> bind(String connectorId, Map<String, Object> settings,
                    WriteMode writeMode, DdlPolicy ddl, TargetTable target) {
                return (SupplierEx<SinkWriter>) () -> null;
            }

            @Override
            public SupplierEx<? extends SinkWriter> bind(String connectorId, Map<String, Object> settings,
                    WriteMode writeMode, DdlPolicy ddl, Map<String, TargetTable> targets) {
                bound.set(targets);
                return (SupplierEx<SinkWriter>) () -> null;
            }
        };
    }

    /** Two single-table sources, the deployment's managed store, and a nest pipeline declaring a view. */
    private static InMemoryStorePort seedStore() {
        InMemoryArtifactStore artifacts = new InMemoryArtifactStore();
        artifacts.save(source(PARENT_SOURCE, PARENT_TABLE));
        artifacts.save(source(CHILD_SOURCE, CHILD_TABLE));
        // The view names no target of its own: the store it lands in is the deployment's.
        artifacts.save(new SourceResource(ViewTargetResolver.STATE_STORE_SOURCE_ID, null, "fake",
                Map.of("host", "d"), null, null, null, null, null));

        Embed item = new Embed("i", Map.of("order_id", "id"), EmbedAs.ARRAY, EMBED_PATH, List.of("id"),
                null, null, null);
        TransformBody.Nest body = new TransformBody.Nest(null, null,
                new NestRoot("o", List.of("id"), null, null, List.of(item)));
        Map<String, FromRef> aliases = new LinkedHashMap<>();
        aliases.put("o", FromRef.literal(PARENT_TABLE));
        aliases.put("i", FromRef.literal(CHILD_TABLE));
        Step step = Step.inline(STEP, FromClause.aliases(aliases), body, null, null);

        // A view and no serve block: declaring the view is the whole instruction to materialize.
        artifacts.save(new PipelineResource(PIPELINE, null, List.of(PARENT_SOURCE, CHILD_SOURCE),
                List.of(step),
                new ViewBlock.Inline(VIEW, FromRef.literal(STEP), "id", null, null),
                null,
                new Settings(null, null, null, null, ReadMode.SNAPSHOT_AND_CDC, "earliest"), null));

        InMemoryStorePort store = new InMemoryStorePort(artifacts);
        store.schemas().save(discovered(PARENT_SOURCE,
                new SourceTable(PARENT_TABLE,
                        List.of(new SourceField("id", "int"), new SourceField("name", "string")),
                        List.of("id"), List.of())));
        store.schemas().save(discovered(CHILD_SOURCE,
                new SourceTable(CHILD_TABLE,
                        List.of(new SourceField("id", "int"), new SourceField("order_id", "int"),
                                new SourceField("sku", "string")),
                        List.of("id"), List.of())));
        return store;
    }

    private static SourceResource source(String id, String table) {
        return new SourceResource(id, null, "fake", Map.of("host", "h"), SourceMode.CDC,
                List.of(TableRef.literal(table)), null, null, null);
    }

    private static DiscoveredSourceModel discovered(String connectionId, SourceTable table) {
        return new DiscoveredSourceModel(connectionId, "fake", 0L, new SourceModel(List.of(table)));
    }
}
