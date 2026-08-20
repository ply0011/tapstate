package io.tapstate.control.restapi;

import static org.assertj.core.api.Assertions.assertThat;

import io.tapstate.control.core.DataBrowserCriteria;
import java.net.URI;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a follow's handshake says: which collection is being followed, and which filter narrows it.
 *
 * <p>Both are read off the handshake URI rather than bound by the container, so both are this class's
 * own reading — and each has a way of being wrong that answers rather than refuses. A name decoded
 * twice resolves to a different collection; a filter matched as a prefix of the query is silently no
 * filter at all, and the reader is shown every row without being told.
 */
class DataBrowserFollowHandshakeTest {

    @Test
    @DisplayName("a collection whose name holds a plus is that name, not the same name with a space")
    void doesNotDecodeAnAlreadyDecodedPath() {
        URI followed = URI.create("http://host/api/data-browser/views/a%2Bb/tail");

        assertThat(DataBrowserTailHandler.segmentBefore(followed.getPath(), "data-browser", 2))
                .isEqualTo("a+b");
        assertThat(DataBrowserTailHandler.segmentBefore(followed.getPath(), "data-browser", 1))
                .isEqualTo("views");
    }

    @Test
    @DisplayName("the filter is read wherever in the query it was written")
    void readsTheFilterAsAParameterRatherThanAsAPrefix() {
        DataBrowserCriteria first = DataBrowserTailHandler.filterOf(URI.create(
                "http://host/api/data-browser/views/orders/tail?filter=%7B%22field%22%3A%22status%22%2C"
                        + "%22op%22%3A%22eq%22%2C%22value%22%3A%22Paid%22%7D"));
        DataBrowserCriteria second = DataBrowserTailHandler.filterOf(URI.create(
                "http://host/api/data-browser/views/orders/tail?since=1&filter=%7B%22field%22%3A%22status"
                        + "%22%2C%22op%22%3A%22eq%22%2C%22value%22%3A%22Paid%22%7D"));

        // Written second it is the same filter, not silently no filter: the discriminating half is that
        // a prefix match answers null here and the follow then streams every row.
        assertThat(first).isNotNull();
        assertThat(second).isEqualTo(first);
    }

    @Test
    @DisplayName("a handshake with no filter asks for every row")
    void answersNothingWhenNoFilterWasWritten() {
        assertThat(DataBrowserTailHandler.filterOf(URI.create(
                "http://host/api/data-browser/views/orders/tail"))).isNull();
        assertThat(DataBrowserTailHandler.filterOf(URI.create(
                "http://host/api/data-browser/views/orders/tail?since=1"))).isNull();
    }
}
