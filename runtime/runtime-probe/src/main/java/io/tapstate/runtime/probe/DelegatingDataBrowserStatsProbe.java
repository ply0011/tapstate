package io.tapstate.runtime.probe;

import io.tapstate.spi.store.ConnectionConfig;
import io.tapstate.spi.store.DataBrowser;
import io.tapstate.spi.store.DataBrowserTableInfo;
import java.util.Objects;

/**
 * The runtime-side stats probe: it fulfils the synchronous control-to-runtime seam by driving the
 * data-browser execution port and returning what it reported. The browser implementation is injected
 * by the app assembly root, so the runtime ring never compiles against an adapter; when the control
 * and runtime roles split, this probe is the engine-side handler control reaches across instances.
 */
public final class DelegatingDataBrowserStatsProbe implements DataBrowserStatsProbe {

    private final DataBrowser browser;

    public DelegatingDataBrowserStatsProbe(DataBrowser browser) {
        this.browser = Objects.requireNonNull(browser, "browser");
    }

    @Override
    public DataBrowserTableInfo stats(ConnectionConfig config, String collection) {
        return browser.stats(config, collection);
    }
}
