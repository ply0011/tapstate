package io.tapstate.control.core;

import io.tapstate.core.common.TapstateException;
import io.tapstate.core.model.SourceResource;
import io.tapstate.runtime.probe.DataBrowserCollectionsProbe;
import io.tapstate.runtime.probe.DataBrowserFindProbe;
import io.tapstate.runtime.probe.DataBrowserStatsProbe;
import io.tapstate.spi.store.ArtifactStore;
import io.tapstate.spi.store.ConnectionConfig;
import io.tapstate.spi.store.DataBrowserPreview;
import io.tapstate.spi.store.DataBrowserQuery;
import io.tapstate.spi.store.DataBrowserTableInfo;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The control plane's three data-browser verbs: list a declared source's collections, report one
 * collection's size, read one collection's rows. Each resolves the source to the connection its reads
 * run on and drives one of the whitelisted runtime probes; the probes are injected, so when the
 * control and runtime roles split the same calls travel across instances.
 *
 * <p>Nothing here persists and nothing is audited — these verbs look, they do not touch. That is what
 * separates them from the two connection probes, which store what they found.
 *
 * <p>A browsable source is any stored {@link SourceResource}, and the collections are the ones its
 * connected database actually holds rather than the ones it declared. Those are different sets, and
 * the declared one is the wrong answer: a source referenced purely as a connection supplier may
 * declare no tables at all, which is exactly the shape the bundled preview store ships in.
 *
 * <p>Which database a read reaches follows from that source's own connection. The probes take no
 * database and the query request has no field for one, so nothing a caller sends can move a read off
 * the connection it was resolved from — the control plane's own tables sit on the same server.
 */
public final class DataBrowserService {

    private final ArtifactStore store;
    private final DataBrowserCollectionsProbe collectionsProbe;
    private final DataBrowserStatsProbe statsProbe;
    private final DataBrowserFindProbe findProbe;

    public DataBrowserService(
            ArtifactStore store,
            DataBrowserCollectionsProbe collectionsProbe,
            DataBrowserStatsProbe statsProbe,
            DataBrowserFindProbe findProbe) {
        this.store = Objects.requireNonNull(store, "store");
        this.collectionsProbe = Objects.requireNonNull(collectionsProbe, "collectionsProbe");
        this.statsProbe = Objects.requireNonNull(statsProbe, "statsProbe");
        this.findProbe = Objects.requireNonNull(findProbe, "findProbe");
    }

    /** Lists the collections the source's own database holds, in the order the connector reports them. */
    public List<String> collections(String sourceId) {
        return collectionsProbe.collections(connection(sourceId));
    }

    /** Reports what the connector knows about one of the source's collections. */
    public DataBrowserTableInfo stats(String sourceId, String collection) {
        ConnectionConfig config = requireCollection(sourceId, collection);
        return statsProbe.stats(config, collection);
    }

    /** Reads up to {@code limit} rows matching {@code filter} from one of the source's collections. */
    public DataBrowserPreview find(
            String sourceId, String collection, Map<String, Object> filter, int limit) {
        ConnectionConfig config = requireCollection(sourceId, collection);
        return findProbe.find(config, new DataBrowserQuery(collection, filter, limit));
    }

    /**
     * The connection a read of {@code collection} runs on, refused with a code if that collection is
     * not one the source's database holds.
     *
     * <p>The check happens here, before the read, because the read cannot report it afterwards. A query
     * against a collection that does not exist comes back empty — the same answer as a collection that
     * holds nothing — so a caller who mistyped a name would be told, indistinguishably, that their data
     * is not there. Paying one listing per read buys a refusal that names what was wrong.
     */
    private ConnectionConfig requireCollection(String sourceId, String collection) {
        ConnectionConfig config = connection(sourceId);
        if (!collectionsProbe.collections(config).contains(collection)) {
            throw new TapstateException(
                    DataBrowserError.UNKNOWN_COLLECTION,
                    Map.of("source", sourceId, "collection", collection),
                    null);
        }
        return config;
    }

    /** The stored source's connection, or a coded not-found naming the id the caller asked for. */
    private ConnectionConfig connection(String sourceId) {
        Objects.requireNonNull(sourceId, "sourceId");
        SourceResource source = store.get(sourceId)
                .filter(SourceResource.class::isInstance)
                .map(SourceResource.class::cast)
                .orElseThrow(() -> new TapstateException(
                        SourceError.NOT_FOUND, Map.of("id", sourceId), null));
        return new ConnectionConfig(source.id(), source.connector(), source.config());
    }
}
