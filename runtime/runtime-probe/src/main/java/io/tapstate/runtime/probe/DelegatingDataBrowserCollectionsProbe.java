package io.tapstate.runtime.probe;

import io.tapstate.spi.store.ConnectionConfig;
import io.tapstate.spi.store.DataBrowser;
import java.util.List;
import java.util.Objects;

/**
 * The runtime-side collections probe: it fulfils the synchronous control-to-runtime seam by driving
 * the data-browser execution port and returning what it listed. The browser implementation — the one
 * that opens the connector through the PDK — is injected by the app assembly root, so the runtime ring
 * never compiles against an adapter. In the first landing this delegation is a direct in-process call;
 * when the control and runtime roles split, this probe is the engine-side handler control reaches
 * across instances.
 *
 * <p>The three data-browser probes share one browser rather than holding one each: the connector
 * instances a read runs on are pooled, and three pools where one is meant would open three times the
 * connections and hand back only the one whose probe was closed.
 */
public final class DelegatingDataBrowserCollectionsProbe implements DataBrowserCollectionsProbe {

    private final DataBrowser browser;

    public DelegatingDataBrowserCollectionsProbe(DataBrowser browser) {
        this.browser = Objects.requireNonNull(browser, "browser");
    }

    @Override
    public List<String> collections(ConnectionConfig config) {
        return browser.collections(config);
    }
}
