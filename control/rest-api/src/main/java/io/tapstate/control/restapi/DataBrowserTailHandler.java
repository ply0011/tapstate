package io.tapstate.control.restapi;

import io.tapstate.control.core.DataBrowserCriteria;
import io.tapstate.control.core.DataBrowserService;
import io.tapstate.core.common.JsonWriter;
import io.tapstate.core.common.TapstateException;
import io.tapstate.control.core.DataBrowserChangeEvent;
import io.tapstate.control.core.DataBrowserFollow;
import io.tapstate.control.core.DataBrowserChangeSink;
import io.tapstate.control.core.DataBrowserError;
import io.tapstate.control.core.DataBrowserFollows;
import io.tapstate.core.common.TapstateErrorCode;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

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
final class DataBrowserTailHandler extends TextWebSocketHandler implements DataBrowserFollows {

    /** How much unsent text one slow reader may accumulate before its own stream is cut. */
    private static final int SEND_BUFFER_BYTES = 512 * 1024;

    /** How long one send may block before the same. */
    private static final int SEND_TIME_LIMIT_MILLIS = 10_000;

    /** The websocket close code for a refusal the client cannot fix by reconnecting. */
    private static final int POLICY_VIOLATION = 1008;

    /**
     * How long a follow may show nothing before it is reclaimed. What it holds is a connector
     * instance and a place in the host's ceiling, and nothing evicts one to make room, so a follow
     * left running by somebody who walked away is a place no other reader can ever have. Idleness is
     * measured in changes shown, not in the connection being alive: a reader who walked away leaves a
     * connection that answers perfectly well, which is exactly the case this exists to end.
     */
    private static final Duration IDLE_LIMIT = Duration.ofMinutes(10);

    /** How often the idle sweep looks. Fine enough that the limit means roughly what it says. */
    private static final Duration SWEEP_INTERVAL = Duration.ofSeconds(30);

    private final DataBrowserService browser;

    /** The live follow per open session, so the close callback releases exactly that session's. */
    private final Map<String, DataBrowserFollow> follows = new ConcurrentHashMap<>();

    /**
     * Which source each open session is following, so a deleted source can be found among them. Kept
     * beside the follows rather than derived from the path on demand: by the time a delete asks, the
     * question is which sessions to stop, and walking every open session's URI to answer it would be
     * the same map built worse.
     */
    private final Map<String, String> sources = new ConcurrentHashMap<>();

    /** When each open session was last shown a change, so the sweep can tell which have gone quiet. */
    private final Map<String, Instant> lastShown = new ConcurrentHashMap<>();

    private final Clock clock;

    private final Duration idleLimit;

    private final ScheduledExecutorService sweeps;

    DataBrowserTailHandler(DataBrowserService browser) {
        this(browser, Clock.systemUTC(), IDLE_LIMIT);
    }

    DataBrowserTailHandler(DataBrowserService browser, Clock clock, Duration idleLimit) {
        this.browser = Objects.requireNonNull(browser, "browser");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.idleLimit = Objects.requireNonNull(idleLimit, "idleLimit");
        this.sweeps = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "data-browser-follow-sweep");
            thread.setDaemon(true);
            return thread;
        });
        this.sweeps.scheduleWithFixedDelay(this::sweepIdle,
                SWEEP_INTERVAL.toMillis(), SWEEP_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Ends every follow that has shown nothing for longer than the limit, telling each reader why.
     *
     * <p>Package-private and driven by the scheduler rather than being the scheduler: a limit measured
     * in minutes is not a thing to prove by waiting, so the case sets the clock and calls this.
     */
    void sweepIdle() {
        Instant deadline = clock.instant().minus(idleLimit);
        lastShown.entrySet().stream()
                .filter(shown -> shown.getValue().isBefore(deadline))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(session -> endFollow(session, DataBrowserError.FOLLOW_IDLE));
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        WebSocketSession buffered = new ConcurrentWebSocketSessionDecorator(
                session, SEND_TIME_LIMIT_MILLIS, SEND_BUFFER_BYTES);
        String path = session.getUri().getPath();
        try {
            String source = segmentBefore(path, "data-browser", 1);
            lastShown.put(session.getId(), clock.instant());
            DataBrowserFollow follow = browser.tail(
                    source,
                    segmentBefore(path, "data-browser", 2),
                    filterOf(session.getUri()),
                    new DataBrowserChangeSink() {
                        @Override
                        public void onChange(DataBrowserChangeEvent change) {
                            lastShown.put(session.getId(), clock.instant());
                            send(buffered, frame(change));
                        }

                        @Override
                        public void onEnded(TapstateErrorCode reason) {
                            // The stream is over; the connection is not, and a reader on an open
                            // connection to an ended stream is watching what looks like a collection
                            // nobody is changing. Closing it is what tells them, the same as a
                            // deleted source does.
                            endFollow(session.getId(), reason);
                        }
                    });
            follows.put(session.getId(), follow);
            sources.put(session.getId(), source);
            sessions.put(session.getId(), session);
        } catch (TapstateException refused) {
            // A refusal here can never be served: the source or the collection does not exist, or the
            // host is holding every connector instance it will hold. Reconnecting would be refused
            // identically, so the close carries the code rather than dropping the connection silently.
            session.close(new CloseStatus(POLICY_VIOLATION, refused.code().code()));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        lastShown.remove(session.getId());
        sources.remove(session.getId());
        sessions.remove(session.getId());
        DataBrowserFollow follow = follows.remove(session.getId());
        if (follow != null) {
            follow.close();
        }
    }

    /**
     * Stops every follow of a source that has just been deleted, and closes the sessions watching it.
     *
     * <p>Stopping the stream alone would leave the reader on a connection that has simply gone quiet,
     * which is indistinguishable from a collection nothing is happening to. The close is what tells
     * them, so both happen here.
     */
    @Override
    public void closeFollowsOf(String sourceId) {
        sources.entrySet().stream()
                .filter(watching -> watching.getValue().equals(sourceId))
                .map(Map.Entry::getKey)
                .toList()
                .forEach(session -> endFollow(session, DataBrowserError.SOURCE_DELETED));
    }

    /**
     * Stops one follow and closes the connection carrying it, naming why. Every way a follow ends
     * without the reader letting go comes through here, so none of them can end in silence.
     */
    private void endFollow(String sessionId, TapstateErrorCode reason) {
        lastShown.remove(sessionId);
        sources.remove(sessionId);
        DataBrowserFollow follow = follows.remove(sessionId);
        if (follow != null) {
            follow.close();
        }
        WebSocketSession open = open(sessionId);
        if (open != null) {
            closeQuietly(open, reason);
        }
    }

    /** The open session behind an id, or null once it has gone. */
    private WebSocketSession open(String sessionId) {
        return sessions.get(sessionId);
    }

    private static void closeQuietly(WebSocketSession session, TapstateErrorCode reason) {
        try {
            session.close(new CloseStatus(POLICY_VIOLATION, reason.code()));
        } catch (IOException alreadyGone) {
            // The peer is already away; there is nothing left to tell.
        }
    }

    /** Stops every live follow — the shutdown path, so a restart does not leave instances held. */
    void closeAll() {
        follows.values().forEach(DataBrowserFollow::close);
        follows.clear();
        // And the sockets, for the reason a deleted source closes them: a stopped follow on an open
        // connection is a collection nothing is happening to, and the reader cannot tell the two apart.
        sessions.values().forEach(session -> closeQuietly(session, DataBrowserError.FOLLOW_STOPPED));
        sources.clear();
        sessions.clear();
        lastShown.clear();
        sweeps.shutdownNow();
    }

    /** The open sessions, so a deleted source's watchers can be told rather than left in silence. */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    /**
     * One change, as the same compact JSON the read faces answer with, so a client decodes a streamed
     * row with the decoder it already has for a read one.
     */
    private static String frame(DataBrowserChangeEvent change) {
        Map<String, Object> frame = new LinkedHashMap<>();
        frame.put("kind", change.kind().name());
        frame.put("at", change.at());
        // Each row is present only when the change carried it. Sending an empty one instead would say
        // the connector supplied a row with nothing in it, which is a different fact from silence.
        if (change.before() != null) {
            frame.put("before", change.before());
        }
        if (change.after() != null) {
            frame.put("after", change.after());
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
    static DataBrowserCriteria filterOf(URI handshake) {
        String query = handshake.getRawQuery();
        if (query == null) {
            return null;
        }
        // Read as one parameter among however many were sent, rather than as a prefix of the whole
        // query. Matched on the prefix, a filter that is not written first is silently not a filter at
        // all — the follow streams every row and says nothing — and one that is written first swallows
        // every parameter after it into its own text.
        for (String parameter : query.split("&")) {
            int assigned = parameter.indexOf('=');
            if (assigned > 0 && parameter.substring(0, assigned).equals("filter")) {
                return WrittenFilter.of(URLDecoder.decode(
                        parameter.substring(assigned + 1), StandardCharsets.UTF_8));
            }
        }
        return null;
    }

    /**
     * The {@code n}-th path segment after {@code marker}. Read positionally rather than by pattern
     * because a collection name may itself hold characters a pattern would have to escape, and the
     * only two segments here are the source and the collection.
     */
    static String segmentBefore(String path, String marker, int offset) {
        // Taken as it is: getPath() has already decoded the escapes, so decoding again reads a name that
        // holds a plus as one holding a space, and one holding a percent as a broken escape — which
        // leaves through this handler as a bare failure rather than as a coded close. The read verbs bind
        // the same two segments through the container, which decodes exactly once, so a second decode
        // here is also the two faces disagreeing about which collection was named.
        String[] segments = path.split("/");
        for (int i = 0; i + offset < segments.length; i++) {
            if (segments[i].equals(marker)) {
                return segments[i + offset];
            }
        }
        throw new IllegalStateException("no source and collection in follow path: " + path);
    }
}
