package io.tapstate.runtime.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tapstate.spi.store.ConnectionConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DelegatingDataBrowserCollectionsProbeTest {

    @Test
    void drivesTheBrowserWithTheConfigAndReturnsItsListing() {
        ConnectionConfig config = new ConnectionConfig("views", "mongodb", Map.of("database", "shop"));
        List<String> expected = List.of("order_state", "customers");
        RecordingDataBrowser browser = RecordingDataBrowser.listing(expected);

        DataBrowserCollectionsProbe probe = new DelegatingDataBrowserCollectionsProbe(browser);
        List<String> collections = probe.collections(config);

        assertThat(collections).isSameAs(expected);
        assertThat(browser.drivenWith).isSameAs(config);
    }

    @Test
    void requiresABrowser() {
        assertThatThrownBy(() -> new DelegatingDataBrowserCollectionsProbe(null))
                .isInstanceOf(NullPointerException.class);
    }
}
