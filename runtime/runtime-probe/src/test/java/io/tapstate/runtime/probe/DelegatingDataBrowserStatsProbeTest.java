package io.tapstate.runtime.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tapstate.spi.store.ConnectionConfig;
import io.tapstate.spi.store.DataBrowserTableInfo;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DelegatingDataBrowserStatsProbeTest {

    @Test
    void drivesTheBrowserWithTheConfigAndCollectionAndReturnsItsReport() {
        ConnectionConfig config = new ConnectionConfig("views", "mongodb", Map.of("database", "shop"));
        DataBrowserTableInfo expected = new DataBrowserTableInfo(512L, 40960L, 80L);
        RecordingDataBrowser browser = RecordingDataBrowser.reporting(expected);

        DataBrowserStatsProbe probe = new DelegatingDataBrowserStatsProbe(browser);
        DataBrowserTableInfo stats = probe.stats(config, "order_state");

        assertThat(stats).isSameAs(expected);
        assertThat(browser.drivenWith).isSameAs(config);
        assertThat(browser.drivenCollection).isEqualTo("order_state");
    }

    @Test
    void requiresABrowser() {
        assertThatThrownBy(() -> new DelegatingDataBrowserStatsProbe(null))
                .isInstanceOf(NullPointerException.class);
    }
}
