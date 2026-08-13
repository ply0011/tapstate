package io.tapstate.runtime.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tapstate.spi.store.ConnectionConfig;
import io.tapstate.spi.store.DataBrowserQuery;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DelegatingDataBrowserFindProbeTest {

    @Test
    void drivesTheBrowserWithTheConfigAndQueryAndReturnsItsRows() {
        ConnectionConfig config = new ConnectionConfig("views", "mongodb", Map.of("database", "shop"));
        DataBrowserQuery query = new DataBrowserQuery("order_state", Map.of("status", "paid"), 10);
        List<Map<String, Object>> expected = List.of(Map.of("order_id", "ord_123"));
        RecordingDataBrowser browser = RecordingDataBrowser.matching(expected);

        DataBrowserFindProbe probe = new DelegatingDataBrowserFindProbe(browser);
        List<Map<String, Object>> rows = probe.find(config, query);

        assertThat(rows).isSameAs(expected);
        assertThat(browser.drivenWith).isSameAs(config);
        assertThat(browser.drivenQuery).isSameAs(query);
    }

    @Test
    void requiresABrowser() {
        assertThatThrownBy(() -> new DelegatingDataBrowserFindProbe(null))
                .isInstanceOf(NullPointerException.class);
    }
}
