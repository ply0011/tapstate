package io.tapstate.runtime.probe;

import io.tapstate.spi.store.ConnectionConfig;
import io.tapstate.spi.store.DataBrowser;
import io.tapstate.spi.store.DataBrowserQuery;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The runtime-side find probe: it fulfils the synchronous control-to-runtime seam by driving the
 * data-browser execution port and returning the rows it matched. The browser implementation is
 * injected by the app assembly root, so the runtime ring never compiles against an adapter; when the
 * control and runtime roles split, this probe is the engine-side handler control reaches across
 * instances.
 *
 * <p>The request is handed over as it arrived. Nothing is added to it here — in particular neither the
 * command a connector dispatches on nor the database a read may touch, both of which the port
 * assembles from the connection precisely so that no layer between the caller and the connector can
 * supply them.
 */
public final class DelegatingDataBrowserFindProbe implements DataBrowserFindProbe {

    private final DataBrowser browser;

    public DelegatingDataBrowserFindProbe(DataBrowser browser) {
        this.browser = Objects.requireNonNull(browser, "browser");
    }

    @Override
    public List<Map<String, Object>> find(ConnectionConfig config, DataBrowserQuery query) {
        return browser.find(config, query);
    }
}
