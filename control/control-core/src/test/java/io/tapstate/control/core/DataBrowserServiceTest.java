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
import io.tapstate.spi.store.DataBrowserPreview;
import io.tapstate.spi.store.DataBrowserQuery;
import io.tapstate.spi.store.DataBrowserSort;
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

    /** What a probe answers when a test does not care what came back. */
    private static final DataBrowserPreview NOTHING = new DataBrowserPreview(List.of(), null, false);

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
                (config, query) -> NOTHING);

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
                    return NOTHING;
                });

        assertThatThrownBy(() -> service.find("views", "absent", Map.of(), null, 10))
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
                (config, query) -> NOTHING);

        assertThat(service.stats("views", "order_state")).isSameAs(expected);
        assertThat(read.get()).isEqualTo("order_state");
    }

    @Test
    void carriesTheCollectionFilterAndLimitIntoTheQueryTheFindProbeIsDrivenWith() {
        AtomicReference<DataBrowserQuery> driven = new AtomicReference<>();
        DataBrowserPreview expected =
                new DataBrowserPreview(List.of(Map.of("order_id", "ord_123")), 512L, true);
        DataBrowserService service = new DataBrowserService(
                store(VIEWS),
                config -> List.of("order_state"),
                (config, collection) -> null,
                (config, query) -> {
                    driven.set(query);
                    return expected;
                });

        DataBrowserPreview preview =
                service.find("views", "order_state", Map.of("status", "paid"), null, 25);

        assertThat(preview).isSameAs(expected);
        assertThat(driven.get().collection()).isEqualTo("order_state");
        assertThat(driven.get().filter()).containsEntry("status", "paid");
        assertThat(driven.get().limit()).isEqualTo(25);
    }

    @Test
    void carriesTheRequestedOrderIntoTheQueryTheFindProbeIsDrivenWith() {
        AtomicReference<DataBrowserQuery> driven = new AtomicReference<>();
        DataBrowserService service = finding(driven);

        service.find("views", "order_state", Map.of(), new DataBrowserSort("status", DataBrowserSort.Direction.DESC), 10);

        assertThat(driven.get().sort().field()).isEqualTo("status");
        assertThat(driven.get().sort().direction()).isEqualTo(DataBrowserSort.Direction.DESC);
    }

    @Test
    void leavesTheOrderUnsetWhenTheCallerAsksForNone() {
        // No order asked for means the database's own, and that is the request rather than a gap in it.
        // Filling in a default here - any default - is the one thing this face promised not to do, and
        // it would be invisible: the rows would come back in some order and look deliberate.
        AtomicReference<DataBrowserQuery> driven = new AtomicReference<>();
        DataBrowserService service = finding(driven);

        service.find("views", "order_state", Map.of(), null, 10);

        assertThat(driven.get().sort()).isNull();
    }

    @Test
    void appliesTheDefaultSizeWhenTheCallerAsksForNoParticularOne() {
        // The default lives here, once, because four surfaces reach this verb and a default per surface
        // is four defaults that drift apart - and this face's whole claim is that they are one request.
        AtomicReference<DataBrowserQuery> driven = new AtomicReference<>();
        DataBrowserService service = finding(driven);

        service.find("views", "order_state", Map.of(), null, null);

        assertThat(driven.get().limit()).isEqualTo(DataBrowserService.DEFAULT_LIMIT);
        assertThat(DataBrowserService.DEFAULT_LIMIT).isEqualTo(10);
    }

    @Test
    void refusesASizePastTheCap() {
        AtomicReference<DataBrowserQuery> driven = new AtomicReference<>();
        DataBrowserService service = finding(driven);

        assertThatThrownBy(() -> service.find("views", "order_state", Map.of(), null, 201))
                .isInstanceOf(TapstateException.class)
                .satisfies(failure -> {
                    TapstateException coded = (TapstateException) failure;
                    assertThat(coded.code().code()).isEqualTo("data-browser.invalid-limit");
                    assertThat(coded.args()).containsEntry("limit", "201").containsEntry("max", "200");
                });
        assertThat(driven.get()).isNull();
    }

    @Test
    void acceptsASizeThatLandsExactlyOnTheCap() {
        // The boundary the refusal is written against: a cap that refuses the value it names would make
        // the number in the message a lie, and nothing else would report it.
        AtomicReference<DataBrowserQuery> driven = new AtomicReference<>();
        DataBrowserService service = finding(driven);

        service.find("views", "order_state", Map.of(), null, DataBrowserService.MAX_LIMIT);

        assertThat(driven.get().limit()).isEqualTo(200);
    }

    @Test
    void refusesASizeThatWouldReadNothing() {
        // Zero and below are not small reads, they are unanswerable ones: the answer would be no rows
        // plus "there is more", which reads as an empty collection with a contradiction attached.
        DataBrowserService service = finding(new AtomicReference<>());

        for (int unreadable : new int[] {0, -1}) {
            assertThatThrownBy(() -> service.find("views", "order_state", Map.of(), null, unreadable))
                    .isInstanceOf(TapstateException.class)
                    .extracting(failure -> ((TapstateException) failure).code().code())
                    .isEqualTo("data-browser.invalid-limit");
        }
    }

    @Test
    void refusesASizeBeforeItCostsAListingToCheckTheCollection() {
        // Resolving the collection is a round trip to the connector. A request that cannot be served
        // whatever comes back should not pay for it - and should not reach a connector at all.
        AtomicReference<ConnectionConfig> listed = new AtomicReference<>();
        DataBrowserService service = new DataBrowserService(
                store(VIEWS),
                config -> {
                    listed.set(config);
                    return List.of("order_state");
                },
                (config, collection) -> null,
                (config, query) -> NOTHING);

        assertThatThrownBy(() -> service.find("views", "order_state", Map.of(), null, 500))
                .isInstanceOf(TapstateException.class);

        assertThat(listed.get()).isNull();
    }

    // ---- fixtures --------------------------------------------------------------------------------

    /** A service over a source holding one collection, recording the query its find probe is driven with. */
    private static DataBrowserService finding(AtomicReference<DataBrowserQuery> driven) {
        return new DataBrowserService(
                store(VIEWS),
                config -> List.of("order_state"),
                (config, collection) -> null,
                (config, query) -> {
                    driven.set(query);
                    return NOTHING;
                });
    }

    private static DataBrowserService service(ArtifactStore store, DataBrowserCollectionsProbe listing) {
        return new DataBrowserService(
                store, listing, (config, collection) -> null, (config, query) -> NOTHING);
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
