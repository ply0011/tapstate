package io.tapstate.runtime.probe;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tapstate.spi.store.ConnectionConfig;
import io.tapstate.spi.store.DataBrowserChange;
import io.tapstate.spi.store.DataBrowserSubscription;
import io.tapstate.spi.store.DataBrowserTailRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DelegatingDataBrowserTailProbeTest {

    private static final ConnectionConfig CONFIG =
            new ConnectionConfig("views", "mongodb", Map.of("database", "shop"));
    private static final DataBrowserTailRequest REQUEST = new DataBrowserTailRequest("order_state");

    @Test
    void drivesTheBrowserWithTheConfigAndRequestItWasGiven() {
        RecordingDataBrowser browser = RecordingDataBrowser.following();

        new DelegatingDataBrowserTailProbe(browser).tail(CONFIG, REQUEST, change -> { });

        assertThat(browser.tailed).containsExactly("views/order_state");
    }

    @Test
    void handsTheListenerThroughSoTheStoresChangesReachTheCaller() {
        // A follow whose changes stop at this seam still runs and still holds a connector, and reports
        // nothing -- which reads to the caller exactly like a table that is not changing.
        RecordingDataBrowser browser = RecordingDataBrowser.following();
        List<DataBrowserChange> seen = new ArrayList<>();

        new DelegatingDataBrowserTailProbe(browser).tail(CONFIG, REQUEST, seen::add);
        DataBrowserChange arrived = new DataBrowserChange(
                DataBrowserChange.Kind.INSERT, null, Map.of("order_id", "ord_123"), 1_700_000_000_000L);
        browser.deliver(arrived);

        assertThat(seen).containsExactly(arrived);
    }

    @Test
    void handsBackTheStoresOwnFollowSoClosingItReachesTheStore() {
        // The close is the one thing here that must not be wrapped or swallowed: closing is what gives a
        // connector instance and its place in the host-wide ceiling back, so a seam that dropped it would
        // leak with nothing to report it -- the caller closed, and the count never came down.
        RecordingDataBrowser browser = RecordingDataBrowser.following();

        DataBrowserSubscription follow =
                new DelegatingDataBrowserTailProbe(browser).tail(CONFIG, REQUEST, change -> { });

        assertThat(browser.closedFollows).as("nothing is released before the caller closes").isZero();
        follow.close();
        assertThat(browser.closedFollows).as("the caller's close reached the store").isEqualTo(1);
    }

    @Test
    void requiresABrowser() {
        assertThatThrownBy(() -> new DelegatingDataBrowserTailProbe(null))
                .isInstanceOf(NullPointerException.class);
    }
}
