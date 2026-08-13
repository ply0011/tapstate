package io.tapstate.control.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tapstate.core.common.TapstateException;
import io.tapstate.core.model.PipelineResource;
import io.tapstate.core.model.Resource;
import io.tapstate.core.model.SourceResource;
import io.tapstate.runtime.probe.DataBrowserCollectionsProbe;
import io.tapstate.spi.store.ArtifactStore;
import io.tapstate.spi.store.ConnectionConfig;
import io.tapstate.spi.store.DataBrowserQuery;
import io.tapstate.spi.store.DataBrowserTableInfo;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * The control plane's three data-browser verbs: resolving a declared source to the connection its
 * reads run on, refusing a name that is not one, and driving the whitelisted probes.
 */
class DataBrowserServiceTest {

    private static final SourceResource VIEWS = new SourceResource(
            "views", null, "mongodb",
            Map.of("uri", "mongodb://db.local", "database", "shop"),
            null, null, null, null, null);

    @Test
    void listsThroughTheProbeOnTheSourcesOwnConnection() {
        AtomicReference<ConnectionConfig> driven = new AtomicReference<>();
        DataBrowserService service = service(store(VIEWS), config -> {
            driven.set(config);
            return List.of("order_state", "customers");
        });

        assertThat(service.collections("views")).containsExactly("order_state", "customers");
        assertThat(driven.get().id()).isEqualTo("views");
        assertThat(driven.get().connectorId()).isEqualTo("mongodb");
        assertThat(driven.get().settings()).containsEntry("database", "shop");
    }

    @Test
    void refusesASourceIdThatIsNotStored() {
        DataBrowserService service = service(store(VIEWS), config -> List.of());

        assertThatThrownBy(() -> service.collections("absent"))
                .isInstanceOf(TapstateException.class)
                .extracting(failure -> ((TapstateException) failure).code().code())
                .isEqualTo("source.not-found");
    }

    @Test
    void refusesAStoredArtifactThatIsNotASource() {
        // A pipeline has an id like a source's and no connection at all; resolving one would otherwise
        // fall through to a null connector and fail somewhere far from the name the user typed.
        Resource pipeline =
                new PipelineResource("orders", null, List.of("views"), null, null, null, null, null);
        DataBrowserService service = service(store(pipeline), config -> List.of());

        assertThatThrownBy(() -> service.collections("orders"))
                .isInstanceOf(TapstateException.class)
                .extracting(failure -> ((TapstateException) failure).code().code())
                .isEqualTo("source.not-found");
    }

    @Test
    void refusesACollectionTheSourcesDatabaseDoesNotHold() {
        DataBrowserService service = service(store(VIEWS), config -> List.of("order_state"));

        assertThatThrownBy(() -> service.stats("views", "absent"))
                .isInstanceOf(TapstateException.class)
                .satisfies(failure -> {
                    TapstateException coded = (TapstateException) failure;
                    assertThat(coded.code().code()).isEqualTo("data-browser.unknown-collection");
                    assertThat(coded.args())
                            .containsEntry("source", "views")
                            .containsEntry("collection", "absent");
                });
    }

    @Test
    void doesNotDriveTheStatsProbeForACollectionThatIsNotThere() {
        // The refusal has to happen before the read, not be mapped out of its failure: a connector
        // reports nothing distinguishable for a collection that is simply absent.
        AtomicReference<String> read = new AtomicReference<>();
        DataBrowserService service = new DataBrowserService(
                store(VIEWS),
                config -> List.of("order_state"),
                (config, collection) -> {
                    read.set(collection);
                    return new DataBrowserTableInfo(0L, 0L, 0L);
                },
                (config, query) -> List.of());

        assertThatThrownBy(() -> service.stats("views", "absent")).isInstanceOf(TapstateException.class);
        assertThat(read.get()).isNull();
    }

    @Test
    void doesNotDriveTheFindProbeForACollectionThatIsNotThere() {
        // The same refusal on the read that would look most complete if it slipped through: a query
        // against a collection that does not exist comes back empty, which reads exactly like a
        // collection that holds nothing.
        AtomicReference<DataBrowserQuery> read = new AtomicReference<>();
        DataBrowserService service = new DataBrowserService(
                store(VIEWS),
                config -> List.of("order_state"),
                (config, collection) -> null,
                (config, query) -> {
                    read.set(query);
                    return List.of();
                });

        assertThatThrownBy(() -> service.find("views", "absent", Map.of(), 10))
                .isInstanceOf(TapstateException.class);
        assertThat(read.get()).isNull();
    }

    @Test
    void reportsWhatTheStatsProbeAnswersForACollectionThatIsThere() {
        DataBrowserTableInfo expected = new DataBrowserTableInfo(512L, 40960L, 80L);
        AtomicReference<String> read = new AtomicReference<>();
        DataBrowserService service = new DataBrowserService(
                store(VIEWS),
                config -> List.of("order_state"),
                (config, collection) -> {
                    read.set(collection);
                    return expected;
                },
                (config, query) -> List.of());

        assertThat(service.stats("views", "order_state")).isSameAs(expected);
        assertThat(read.get()).isEqualTo("order_state");
    }

    @Test
    void carriesTheCollectionFilterAndLimitIntoTheQueryTheFindProbeIsDrivenWith() {
        AtomicReference<DataBrowserQuery> driven = new AtomicReference<>();
        List<Map<String, Object>> expected = List.of(Map.of("order_id", "ord_123"));
        DataBrowserService service = new DataBrowserService(
                store(VIEWS),
                config -> List.of("order_state"),
                (config, collection) -> null,
                (config, query) -> {
                    driven.set(query);
                    return expected;
                });

        List<Map<String, Object>> rows =
                service.find("views", "order_state", Map.of("status", "paid"), 25);

        assertThat(rows).isSameAs(expected);
        assertThat(driven.get().collection()).isEqualTo("order_state");
        assertThat(driven.get().filter()).containsEntry("status", "paid");
        assertThat(driven.get().limit()).isEqualTo(25);
    }

    // ---- fixtures --------------------------------------------------------------------------------

    private static DataBrowserService service(ArtifactStore store, DataBrowserCollectionsProbe listing) {
        return new DataBrowserService(
                store, listing, (config, collection) -> null, (config, query) -> List.of());
    }

    private static ArtifactStore store(Resource... stored) {
        Map<String, Resource> byId = new LinkedHashMap<>();
        for (Resource resource : stored) {
            byId.put(resource.id(), resource);
        }
        return new ArtifactStore() {
            @Override
            public void saveAll(List<Resource> artifacts) {
                artifacts.forEach(artifact -> byId.put(artifact.id(), artifact));
            }

            @Override
            public Optional<Resource> get(String id) {
                return Optional.ofNullable(byId.get(id));
            }

            @Override
            public List<Resource> list() {
                return new ArrayList<>(byId.values());
            }
        };
    }
}
