package io.tapstate.runtime.probe;

import io.tapstate.spi.store.ConnectionConfig;
import io.tapstate.spi.store.DataBrowser;
import io.tapstate.spi.store.DataBrowserChange;
import io.tapstate.spi.store.DataBrowserChangeListener;
import io.tapstate.spi.store.DataBrowserSubscription;
import io.tapstate.spi.store.DataBrowserTailRequest;
import io.tapstate.spi.store.DataBrowserPreview;
import io.tapstate.spi.store.DataBrowserQuery;
import io.tapstate.spi.store.DataBrowserTableInfo;
import java.util.List;

/**
 * A data browser that records what it was driven with and hands back a fixed answer, so each probe
 * test can assert that its one operation reached the port unchanged. The operations a given test does
 * not exercise throw rather than returning a default: a probe that delegated to the wrong one would
 * otherwise pass by returning an empty answer that reads like a real one.
 */
final class RecordingDataBrowser implements DataBrowser {

    private final List<String> collections;
    private final DataBrowserTableInfo stats;
    private final DataBrowserPreview preview;

    ConnectionConfig drivenWith;
    String drivenCollection;
    DataBrowserQuery drivenQuery;
    boolean closed;

    private RecordingDataBrowser(
            List<String> collections, DataBrowserTableInfo stats, DataBrowserPreview preview) {
        this.collections = collections;
        this.stats = stats;
        this.preview = preview;
    }

    static RecordingDataBrowser listing(List<String> collections) {
        return new RecordingDataBrowser(collections, null, null);
    }

    static RecordingDataBrowser reporting(DataBrowserTableInfo stats) {
        return new RecordingDataBrowser(null, stats, null);
    }

    static RecordingDataBrowser matching(DataBrowserPreview preview) {
        return new RecordingDataBrowser(null, null, preview);
    }

    /** A browser primed only for follows, which hand back a handle rather than a fixed answer. */
    static RecordingDataBrowser following() {
        return new RecordingDataBrowser(null, null, null);
    }

    @Override
    public List<String> collections(ConnectionConfig config) {
        drivenWith = config;
        return require(collections, "collections");
    }

    @Override
    public DataBrowserTableInfo stats(ConnectionConfig config, String collection) {
        drivenWith = config;
        drivenCollection = collection;
        return require(stats, "stats");
    }

    @Override
    public DataBrowserPreview find(ConnectionConfig config, DataBrowserQuery query) {
        drivenWith = config;
        drivenQuery = query;
        return require(preview, "find");
    }

    @Override
    public void close() {
        closed = true;
    }

    private static <T> T require(T answer, String operation) {
        if (answer == null) {
            throw new AssertionError("this browser was not primed for " + operation);
        }
        return answer;
    }

    /** The follow requests this browser was asked for, newest last. */
    final java.util.List<String> tailed = new java.util.ArrayList<>();

    /** How many of the follows handed out have been closed. */
    int closedFollows;

    /** The listener the last follow was opened with, so a test can push a change through the seam. */
    DataBrowserChangeListener lastListener;

    @Override
    public DataBrowserSubscription tail(
            ConnectionConfig config, DataBrowserTailRequest request, DataBrowserChangeListener listener) {
        tailed.add(config.id() + "/" + request.collection());
        lastListener = listener;
        return () -> closedFollows++;
    }

    /** Delivers one change as the store would, so what the seam does with it can be asserted. */
    void deliver(DataBrowserChange change) {
        lastListener.onChange(change);
    }
}
