package io.tapstate.app;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tapstate.core.common.TapstateException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;

/**
 * A view's key must be the identity of what feeds it, or the build refuses by name.
 *
 * <p>The view sink upserts every stream on the view's single declared key and builds a unique index on
 * it. Where that key is not the feeding stream's identity, distinct records agree on it: two assembled
 * roots keyed {@code (order_id, tenant_id)} collapse onto one {@code order_id}, and two tables' rows
 * sharing an id land on one document. The write path cannot see either - the collection is right, the
 * count of a single snapshot is right - so the mismatch has to be refused where the pipeline is built,
 * with a coded error, not discovered as missing rows.
 */
class AViewKeyMustBeTheIdentityOfWhatFeedsItTest {

    private static final String PIPELINE = "nested_orders";
    private static final String STEP = "order_doc";

    @Test
    void a_view_key_that_is_not_the_assembled_root_key_is_refused_by_name() {
        // Roots keyed (order_id, tenant_id); the view upserts on order_id alone. Roots (1,'a') and
        // (1,'b') are two documents its unique index will only hold as one.
        InMemoryArtifactStore artifacts = nestWorkspace(List.of("order_id", "tenant_id"), "order_id");

        assertThatThrownBy(() -> dagFor(artifacts))
                .isInstanceOf(TapstateException.class)
                .satisfies(code("actuation.view-key-not-root-key"));
    }

    @Test
    void a_view_key_naming_a_different_column_than_the_root_key_is_refused_the_same_way() {
        // Single-column both sides, different columns: the upsert matches on a column the assembly does
        // not converge on, so every re-sent root lands beside the one it should replace.
        InMemoryArtifactStore artifacts = nestWorkspace(List.of("customer_id"), "order_id");

        assertThatThrownBy(() -> dagFor(artifacts))
                .isInstanceOf(TapstateException.class)
                .satisfies(code("actuation.view-key-not-root-key"));
    }

    @Test
    void a_view_fed_by_more_than_one_table_is_refused_by_name() {
        // One source, two tables, no nest to collapse them: orders.id=1 and invoices.id=1 would take
        // turns overwriting the same document.
        InMemoryArtifactStore artifacts = new InMemoryArtifactStore();
        artifacts.save(new SourceResource("src", null, "fake", Map.of("host", "h"), SourceMode.CDC,
                List.of(TableRef.literal("orders"), TableRef.literal("invoices")), null, null, null));
        artifacts.save(managedStore());
        artifacts.save(new PipelineResource(PIPELINE, null, List.of("src"), null,
                new ViewBlock.Inline("order_state", FromRef.literal("src"), "id", null, null),
                null, settings(), null));

        assertThatThrownBy(() -> dagFor(artifacts))
                .isInstanceOf(TapstateException.class)
                .satisfies(code("actuation.view-fed-by-many-tables"));
    }

    @Test
    void a_capture_source_squatting_on_the_managed_store_id_is_refused_rather_than_written_into() {
        // The store is resolved by its id alone, and "warehouse" is a name an author plausibly gives
        // their own upstream database. A resource under that id that declares capture settings is an
        // authored source, and materializing a view into it would write into a database the deployment
        // does not own - silently, because the id resolved fine.
        InMemoryArtifactStore artifacts = new InMemoryArtifactStore();
        artifacts.save(new SourceResource("src", null, "fake", Map.of("host", "h"), SourceMode.CDC,
                List.of(TableRef.literal("orders")), null, null, null));
        artifacts.save(new SourceResource(ViewTargetResolver.STATE_STORE_SOURCE_ID, null, "mysql",
                Map.of("host", "the-users-own-warehouse"), SourceMode.CDC,
                List.of(TableRef.literal("facts")), null, null, null));
        artifacts.save(new PipelineResource(PIPELINE, null, List.of("src"), null,
                new ViewBlock.Inline("order_state", FromRef.literal("orders"), "id", null, null),
                null, settings(), null));

        assertThatThrownBy(() -> dagFor(artifacts))
                .isInstanceOf(TapstateException.class)
                .satisfies(code("actuation.view-store-is-a-capture-source"));
    }

    @Test
    void a_view_over_a_matching_single_column_root_key_still_builds() {
        Assertions.assertThatCode(() -> dagFor(nestWorkspace(List.of("id"), "id")))
                .doesNotThrowAnyException();
    }

    private static void dagFor(InMemoryArtifactStore artifacts) {
        new StoreBackedDagSource(new InMemoryStorePort(artifacts)).dagFor(PIPELINE);
    }

    private static Consumer<Throwable> code(String expected) {
        return e -> Assertions.assertThat(((TapstateException) e).code().code()).isEqualTo(expected);
    }

    /** Two single-table sources and a nest pipeline; the root key and view key are the variables. */
    private static InMemoryArtifactStore nestWorkspace(List<String> rootKey, String viewKey) {
        InMemoryArtifactStore artifacts = new InMemoryArtifactStore();
        artifacts.save(new SourceResource("src_orders", null, "fake", Map.of("host", "h"), SourceMode.CDC,
                List.of(TableRef.literal("orders")), null, null, null));
        artifacts.save(new SourceResource("src_items", null, "fake", Map.of("host", "h"), SourceMode.CDC,
                List.of(TableRef.literal("order_items")), null, null, null));
        artifacts.save(managedStore());

        // The embed targets the root key exactly, so the nest's own compile gate passes and the
        // refusal under test is the view's.
        Map<String, String> on = new LinkedHashMap<>();
        for (String column : rootKey) {
            on.put("child_" + column, column);
        }
        Embed item = new Embed("i", on, EmbedAs.ARRAY, "items", List.of("id"),
                null, null, null);
        TransformBody.Nest body = new TransformBody.Nest(null, null,
                new NestRoot("o", rootKey, null, null, List.of(item)));
        Map<String, FromRef> aliases = new LinkedHashMap<>();
        aliases.put("o", FromRef.literal("orders"));
        aliases.put("i", FromRef.literal("order_items"));
        Step step = Step.inline(STEP, FromClause.aliases(aliases), body, null, null);

        artifacts.save(new PipelineResource(PIPELINE, null, List.of("src_orders", "src_items"),
                List.of(step),
                new ViewBlock.Inline("order_state", FromRef.literal(STEP), viewKey, null, null),
                null, settings(), null));
        return artifacts;
    }

    private static SourceResource managedStore() {
        return new SourceResource(ViewTargetResolver.STATE_STORE_SOURCE_ID, null, "fake",
                Map.of("host", "d"), null, null, null, null, null);
    }

    private static Settings settings() {
        return new Settings(null, null, null, null, ReadMode.SNAPSHOT_AND_CDC, "earliest");
    }
}
