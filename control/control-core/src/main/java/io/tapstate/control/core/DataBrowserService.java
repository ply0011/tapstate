package io.tapstate.control.core;

import io.tapstate.core.common.TapstateException;
import io.tapstate.core.model.SourceResource;
import io.tapstate.runtime.probe.DataBrowserCollectionsProbe;
import io.tapstate.runtime.probe.DataBrowserFindProbe;
import io.tapstate.runtime.probe.DataBrowserStatsProbe;
import io.tapstate.runtime.probe.DataBrowserTailProbe;
import io.tapstate.spi.store.ArtifactStore;
import io.tapstate.spi.store.ConnectionConfig;
import io.tapstate.spi.store.DataBrowserQuery;
import io.tapstate.spi.store.DataBrowserSubscription;
import io.tapstate.spi.store.DataBrowserTailRequest;
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
 *
 * <p>What crosses in and out is the control ring's own vocabulary, never the storage ports': the surfaces
 * send a {@link DataBrowserSortOrder} and receive a {@link DataBrowserStatsReport} /
 * {@link DataBrowserPreviewReport}, and this class is where those meet the port types. A face that had to
 * name a port type to read a row would be a face reaching past this service.
 */
public final class DataBrowserService {

    /** How many rows a read that asks for no particular number is served. */
    public static final int DEFAULT_LIMIT = 10;

    /**
     * The most rows one read will serve. What keeps a preview a preview: rows are read whole into
     * memory in one shot and handed to a terminal, so an unbounded request would be a way to pull a
     * collection through this face. Taking everything is what the query service is for.
     */
    public static final int MAX_LIMIT = 200;

    private final ArtifactStore store;
    private final DataBrowserCollectionsProbe collectionsProbe;
    private final DataBrowserStatsProbe statsProbe;
    private final DataBrowserFindProbe findProbe;
    private final DataBrowserTailProbe tailProbe;

    public DataBrowserService(
            ArtifactStore store,
            DataBrowserCollectionsProbe collectionsProbe,
            DataBrowserStatsProbe statsProbe,
            DataBrowserFindProbe findProbe,
            DataBrowserTailProbe tailProbe) {
        this.store = Objects.requireNonNull(store, "store");
        this.collectionsProbe = Objects.requireNonNull(collectionsProbe, "collectionsProbe");
        this.statsProbe = Objects.requireNonNull(statsProbe, "statsProbe");
        this.findProbe = Objects.requireNonNull(findProbe, "findProbe");
        this.tailProbe = Objects.requireNonNull(tailProbe, "tailProbe");
    }

    /** Lists the collections the source's own database holds, in the order the connector reports them. */
    public List<String> collections(String sourceId) {
        return collectionsProbe.collections(connection(sourceId));
    }

    /** Reports what the connector knows about one of the source's collections. */
    public DataBrowserStatsReport stats(String sourceId, String collection) {
        ConnectionConfig config = requireCollection(sourceId, collection);
        return DataBrowserStatsReport.from(statsProbe.stats(config, collection));
    }

    /**
     * Reads up to {@code limit} rows matching {@code filter} from one of the source's collections, in
     * {@code sort}'s order when one is asked for.
     *
     * <p>Both {@code sort} and {@code limit} are nullable, and null is a request rather than a gap: no
     * order asked for means the database's own, and no size asked for means this face's default. They
     * are settled here, once, because every surface that offers this verb reaches it — a default per
     * surface would be several defaults drifting apart under a face whose whole claim is that they
     * are one request.
     */
    public DataBrowserPreviewReport find(
            String sourceId,
            String collection,
            DataBrowserCriteria filter,
            DataBrowserSortOrder sort,
            Integer limit) {
        int bound = limit == null ? DEFAULT_LIMIT : limit;
        // Before the collection is resolved, which costs a round trip to the connector: a request that
        // cannot be served whatever comes back should not pay for it, or reach a connector at all.
        if (bound < 1 || bound > MAX_LIMIT) {
            throw new TapstateException(
                    DataBrowserError.INVALID_LIMIT,
                    Map.of("limit", String.valueOf(bound), "max", String.valueOf(MAX_LIMIT)),
                    null);
        }
        ConnectionConfig config = requireCollection(sourceId, collection);
        DataBrowserQuery query = new DataBrowserQuery(
                collection,
                filter == null ? null : filter.toPortRequest(),
                sort == null ? null : sort.toPortRequest(),
                bound);
        return DataBrowserPreviewReport.from(findProbe.find(config, query));
    }

    /**
     * Follows one of the source's collections, delivering each change that {@code filter} admits to
     * {@code listener} until the returned subscription is closed. A null filter admits everything.
     *
     * <p>The same confinement the reads get, applied again on a different road. The follow reaches the
     * store through another function entirely, and that function takes its table from whoever calls it
     * — so the check that the collection is one this source's own database holds has to happen here
     * too. A rule enforced on one of two paths is not a rule.
     *
     * <p>The filter is applied here, to each change as it arrives, and that is a choice with a cost
     * worth stating: it narrows what the caller is shown, never what the store captures. Everything
     * the collection does is still streamed and still costs what it costs. It is not an oversight that
     * it is not pushed down — the read function behind this takes a table, not a query, so there is
     * nowhere to push it to.
     */
    public DataBrowserFollow tail(
            String sourceId,
            String collection,
            DataBrowserCriteria filter,
            DataBrowserChangeSink sink) {
        Objects.requireNonNull(sink, "sink");
        ConnectionConfig config = requireCollection(sourceId, collection);
        DataBrowserSubscription subscription = tailProbe.tail(
                config, new DataBrowserTailRequest(collection), change -> {
            // A removal carries no row to test, and withholding it because a filter cannot be evaluated
            // against nothing would leave a reader watching a row that has quietly gone.
            if (filter == null || change.row() == null || filter.matches(change.row())) {
                sink.onChange(DataBrowserChangeEvent.from(change));
            }
        });
        return subscription::close;
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
