package io.tapstate.control.restapi;

import io.tapstate.control.core.DataBrowserCriteria;
import io.tapstate.control.core.DataBrowserService;
import io.tapstate.core.common.JsonWriter;
import io.tapstate.core.common.TapstateException;
import io.tapstate.control.core.DataBrowserChangeEvent;
import io.tapstate.control.core.DataBrowserFollow;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The followed-collection stream: {@code /api/data-browser/{source}/{collection}/tail}.
 *
 * <p>The third thin stream channel, and the first that carries data rather than an observation of a
 * pipeline. Unlike its two neighbours it does not poll — there is nothing to re-read, the store
 * pushes — so it does not share their scheduler either. That is not a preference: those two hand
 * their work to a small shared pool, and a follow is a blocking read that never returns, so a couple
 * of follows would take that pool and leave every status watch and log follow in the process silently
 * frozen. Each follow instead owns the subscription the browser handed back, which brought its own
 * thread with it.
 *
 * <p>What a follow holds is a connector instance, so closing it is not housekeeping. The close
 * callback is the only thing that gives it back; without it a client that walked away would leave an
 * instance held and counting against the host's ceiling, which shows up later as somebody else being
 * refused, with nothing to connect the two.
 *
 * <p>Sends go through a bounded buffer. Writing straight to the session would block whichever thread
 * called, and here that thread is the one draining the store's change stream — so one slow reader
 * would stop the capture feeding it. Buffered, a reader that cannot keep up is disconnected instead,
 * and only its own follow is affected.
 */
final class DataBrowserTailHandler extends TextWebSocketHandler {

    /** How much unsent text one slow reader may accumulate before its own stream is cut. */
    private static final int SEND_BUFFER_BYTES = 512 * 1024;

    /** How long one send may block before the same. */
    private static final int SEND_TIME_LIMIT_MILLIS = 10_000;

    /** The websocket close code for a refusal the client cannot fix by reconnecting. */
    private static final int POLICY_VIOLATION = 1008;

    private final DataBrowserService browser;

    /** The live follow per open session, so the close callback releases exactly that session's. */
    private final Map<String, DataBrowserFollow> follows = new ConcurrentHashMap<>();

    DataBrowserTailHandler(DataBrowserService browser) {
        this.browser = Objects.requireNonNull(browser, "browser");
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        WebSocketSession buffered = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_BYTES);
        String path = session.getUri().getPath();
        try {
            DataBrowserFollow follow = browser.tail(
                    segmentBefore(path, "data-browser", 1),
                    segmentBefore(path, "data-browser", 2),
                    filterOf(session),
                    change -> send(buffered, frame(change)));
            follows.put(session.getId(), follow);
        } catch (TapstateException refused) {
            // A refusal here can never be served: the source or the collection does not exist, or the
            // host is holding every connector instance it will hold. Reconnecting would be refused
            // identically, so the close carries the code rather than dropping the connection silently.
            session.close(new CloseStatus(POLICY_VIOLATION, refused.code().code()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        DataBrowserFollow follow = follows.remove(session.getId());
        if (follow != null) {
            follow.close();
        }
    }

    /** Stops every live follow — the shutdown path, so a restart does not leave instances held. */
    void closeAll() {
        follows.values().forEach(DataBrowserFollow::close);
        follows.clear();
    }

    /**
     * One change, as the same compact JSON the read faces answer with, so a client decodes a streamed
     * row with the decoder it already has for a read one.
     */
    private static String frame(DataBrowserChangeEvent change) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("kind", change.removed() ? "DELETE" : "UPSERT");
        frame.put("key", change.key());
        frame.put("at", change.at());
        if (change.row() != null) {
            frame.put("row", change.row());
        }
        return JsonWriter.write(frame);
    }

    private static void send(WebSocketSession session, String json) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json));
            }
        } catch (IOException peerWentAway) {
            // The buffer overflowed or the peer closed mid-send; the close callback releases the follow.
        }
    }

    /** The filter a follow was opened with, read from the handshake query, or null when none was given. */
    private static DataBrowserCriteria filterOf(WebSocketSession session) {
        String query = session.getUri().getQuery();
        if (query == null || !query.startsWith("filter=")) {
            return null;
        }
        String written = URLDecoder.decode(query.substring("filter=".length()), StandardCharsets.UTF_8);
        return WrittenFilter.of(written);
    }

    /**
     * The {@code n}-th path segment after {@code marker}. Read positionally rather than by pattern
     * because a collection name may itself hold characters a pattern would have to escape, and the
     * only two segments here are the source and the collection.
     */
    private static String segmentBefore(String path, String marker, int offset) {
        String[] segments = path.split("/");
        for (int i = 0; i + offset < segments.length; i++) {
            if (segments[i].equals(marker)) {
                return URLDecoder.decode(segments[i + offset], StandardCharsets.UTF_8);
            }
        }
        throw new IllegalStateException("no source and collection in follow path: " + path);
    }
}
