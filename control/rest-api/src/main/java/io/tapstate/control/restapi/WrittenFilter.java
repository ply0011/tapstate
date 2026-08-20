package io.tapstate.control.restapi;

import io.tapstate.control.core.DataBrowserCriteria;
import io.tapstate.core.common.JsonReader;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads a filter that arrived as text rather than as a request body.
 *
 * <p>A follow opens over a websocket, and a handshake has no body to bind — so its filter travels in
 * the handshake's query, as the same JSON a read would have sent. That is the whole reason this
 * exists: the shape and every refusal are the read face's, reached by a different road. A second
 * filter grammar for the streamed face would be a second thing to keep in step with the vocabulary,
 * and the two would answer the same written filter differently.
 */
final class WrittenFilter {

    private WrittenFilter() {
    }

    /** The criteria a written filter names, or null when nothing was written. */
    static DataBrowserCriteria of(String written) {
        if (written == null || written.isBlank()) {
            return null;
        }
        Object parsed;
        try {
            parsed = JsonReader.parse(written);
        } catch (RuntimeException notJson) {
            throw MalformedRequest.rejecting("the `filter` is not readable as JSON", notJson);
        }
        return DataBrowserController.criteria(filter(parsed));
    }

    /** One written filter as the request shape, so it meets the same reading every read filter does. */
    private static DataBrowserFindRequest.Filter filter(Object written) {
        if (!(written instanceof Map<?, ?> map)) {
            throw MalformedRequest.rejecting("a `filter` is an object", null);
        }
        return new DataBrowserFindRequest.Filter(
                text(map.get("field")), text(map.get("op")), map.get("value"),
                members(map.get("all")), members(map.get("any")));
    }

    /** The members of a combination, or null when the key was not written at all. */
    private static List<DataBrowserFindRequest.Filter> members(Object written) {
        if (written == null) {
            return null;
        }
        if (!(written instanceof List<?> entries)) {
            throw MalformedRequest.rejecting("a `filter` combination holds a list of filters", null);
        }
        List<DataBrowserFindRequest.Filter> members = new ArrayList<>(entries.size());
        entries.forEach(entry -> members.add(filter(entry)));
        return members;
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
