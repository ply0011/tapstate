package io.tapstate.runtime.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tapstate.spi.store.ConnectionConfig;
import io.tapstate.spi.store.DataBrowserFilter;
import io.tapstate.spi.store.DataBrowserPreview;
import io.tapstate.spi.store.DataBrowserQuery;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DelegatingDataBrowserFindProbeTest {

    @Test
    void drivesTheBrowserWithTheConfigAndQueryAndReturnsItsAnswer() {
        ConnectionConfig config = new ConnectionConfig("views", "mongodb", Map.of("database", "shop"));
        DataBrowserQuery query = new DataBrowserQuery("order_state",
                new DataBrowserFilter.Match("status", DataBrowserFilter.Operator.EQ, "paid"), 10);
        DataBrowserPreview expected =
                new DataBrowserPreview(List.of(Map.of("order_id", "ord_123")), 512L, true);
        RecordingDataBrowser browser = RecordingDataBrowser.matching(expected);

        DataBrowserFindProbe probe = new DelegatingDataBrowserFindProbe(browser);
        DataBrowserPreview preview = probe.find(config, query);

        assertThat(preview).isSameAs(expected);
        assertThat(browser.drivenWith).isSameAs(config);
        assertThat(browser.drivenQuery).isSameAs(query);
    }

    @Test
    void requiresABrowser() {
        assertThatThrownBy(() -> new DelegatingDataBrowserFindProbe(null))
                .isInstanceOf(NullPointerException.class);
    }
}
