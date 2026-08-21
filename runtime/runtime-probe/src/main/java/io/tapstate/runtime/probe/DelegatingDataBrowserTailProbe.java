package io.tapstate.runtime.probe;

import io.tapstate.spi.store.ConnectionConfig;
import io.tapstate.spi.store.DataBrowser;
import io.tapstate.spi.store.DataBrowserChangeListener;
import io.tapstate.spi.store.DataBrowserSubscription;
import io.tapstate.spi.store.DataBrowserTailRequest;
import java.util.Objects;

/**
 * The runtime-side follow probe: it fulfils the synchronous control-to-runtime seam by driving the
 * data-browser execution port and returning the handle on the running stream. The browser
 * implementation is injected by the app assembly root, so the runtime ring never compiles against an
 * adapter; when the control and runtime roles split, this probe is the engine-side handler control
 * reaches across instances.
 *
 * <p>The request is handed over as it arrived, and so is the subscription that comes back. Nothing is
 * wrapped here — in particular the close is not, because closing is what gives a connector instance
 * and its place in the host-wide ceiling back, and a layer that forgot to pass it through would be a
 * leak with nothing to report it.
 */
public final class DelegatingDataBrowserTailProbe implements DataBrowserTailProbe {

    private final DataBrowser browser;

    public DelegatingDataBrowserTailProbe(DataBrowser browser) {
        this.browser = Objects.requireNonNull(browser, "browser");
    }

    @Override
    public DataBrowserSubscription tail(
            ConnectionConfig config, DataBrowserTailRequest request, DataBrowserChangeListener listener) {
        return browser.tail(config, request, listener);
    }
}
