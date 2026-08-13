package io.tapstate.runtime.probe;

import io.tapstate.spi.store.ConnectionConfig;
import io.tapstate.spi.store.DataBrowser;
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
}
