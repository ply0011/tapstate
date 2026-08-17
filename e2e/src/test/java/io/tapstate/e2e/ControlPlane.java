package io.tapstate.e2e;

import io.tapstate.control.core.MonitorError;
import io.tapstate.core.common.JsonReader;
import io.tapstate.core.common.JsonWriter;
import io.tapstate.core.lifecycle.LifecycleVerb;
import io.tapstate.core.lifecycle.PipelineState;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

/**
 * The product's HTTP surface, as a caller sees it.
 *
 * <p>The harness speaks this wire itself rather than borrowing the CLI's client: the CLI's own
 * testing is unit tests, a corpus and a native smoke, and pulling it in here would make every
 * specification a test of two things at once. What is shared with the product on purpose is the JSON
 * codec and the DSL parser - the places where a second implementation would be a second truth.
 *
 * <p>Failures are surfaced, never absorbed: a refused verb fails the specification carrying the
 * server's own status and body, because a harness that turns a 4xx into a quiet nothing would let a
 * broken product pass.
 */
final class ControlPlane {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** How often a caller waiting on a pushed change looks again; a follow is told, never asked. */
    private static final Duration POLL = Duration.ofMillis(100);

    private final URI baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    private String credential;

    ControlPlane(URI baseUrl) {
        this.baseUrl = baseUrl;
    }

    /** Whether the product answers its health probe; the readiness signal a launcher waits on. */
    boolean healthy() {
        try {
            HttpResponse<String> response = send(get("/healthz"));
            return response.statusCode() == 200 && "ok".equals(response.body());
        } catch (UncheckedIOException e) {
            return false;
        }
    }

    /**
     * Creates the first admin and holds its token for every later call. Bootstrap is refused off
     * loopback, which is why both tiers run the server on this machine rather than in a container.
     */
    void bootstrapAndLogin(String username, String password) {
        String body = JsonWriter.write(Map.of("username", username, "password", password));
        expect(send(post("/auth/bootstrap", body)), 204, "bootstrap the first admin");
        HttpResponse<String> login = send(post("/auth/login", body));
        expect(login, 200, "log in");
        if (!(JsonReader.parse(login.body()) instanceof Map<?, ?> map)
                || !(map.get("token") instanceof String token)) {
            throw new AssertionError("login returned no token: " + login.body());
        }
        credential = token;
    }

    /**
     * Applies resource documents as one batch, each named by the file it came from. One call, not one
     * per file: the product resolves references within the submitted set, so resources that name each
     * other have to be submitted together.
     */
    void apply(Map<String, String> contentBySource) {
        List<Map<String, String>> drafts = contentBySource.entrySet().stream()
                .map(entry -> Map.of("source", entry.getKey(), "content", entry.getValue()))
                .toList();
        String body = JsonWriter.write(Map.of("drafts", drafts));
        expect(send(authed("/api/artifacts:apply", body)), 200, "apply " + contentBySource.keySet());
    }

    /**
     * Applies a batch the product is expected to refuse, and returns the refusal it answered with.
     *
     * <p>A separate verb rather than a flag on {@link #apply}, for the same reason registration has one:
     * a caller that meant to apply and was refused has failed, and a caller that meant to witness a
     * refusal and got an apply has failed too. One return value cannot mean both.
     */
    Refusal applyExpectingRefusal(Map<String, String> contentBySource) {
        List<Map<String, String>> drafts = contentBySource.entrySet().stream()
                .map(entry -> Map.of("source", entry.getKey(), "content", entry.getValue()))
                .toList();
        String body = JsonWriter.write(Map.of("drafts", drafts));
        HttpResponse<String> response = send(authed("/api/artifacts:apply", body));
        return interpretRefusal(response.statusCode(), response.body(), "applying " + contentBySource.keySet());
    }

    /** The ids the server holds - read back from the server, which is the truth, not from the files sent. */
    List<String> artifactIds() {
        HttpResponse<String> response = send(authedGet("/api/artifacts"));
        expect(response, 200, "list artifacts");
        if (!(JsonReader.parse(response.body()) instanceof Map<?, ?> map)
                || !(map.get("artifacts") instanceof List<?> artifacts)) {
            throw new AssertionError("artifact list was not a list: " + response.body());
        }
        return artifacts.stream()
                .map(each -> each instanceof Map<?, ?> m ? m.get("id") : null)
                .map(String::valueOf)
                .toList();
    }

    /** Registers a connector's runtime jar; the product makes this idempotent by content hash. */
    void registerConnector(String connectorId, byte[] jar) {
        String body = JsonWriter.write(Map.of("artifact", Base64.getEncoder().encodeToString(jar)));
        expect(send(authed("/api/connectors:register", body)), 200, "register the " + connectorId + " connector");
    }

    /** The refusal a rejected verb answered with: the HTTP status, and the code the product named. */
    record Refusal(int status, String code) {}

    /**
     * Posts an artifact the product is expected to refuse, and returns the refusal it answered with.
     *
     * <p>A separate verb rather than a flag on {@link #registerConnector}: a caller that meant to
     * register and was refused has failed, and a caller that meant to witness a refusal and got a
     * registration has failed too. One return value cannot mean both.
     */
    Refusal registerConnectorExpectingRefusal(byte[] jar) {
        String body = JsonWriter.write(Map.of("artifact", Base64.getEncoder().encodeToString(jar)));
        HttpResponse<String> response = send(authed("/api/connectors:register", body));
        return interpretRefusal(response.statusCode(), response.body(), "registering the artifact");
    }

    /**
     * What an answer to a verb the caller expected to be refused is allowed to mean. Only a client error
     * is a refusal: it is the product having judged the request and declined it, which is the outcome
     * such a caller is witnessing.
     *
     * <p>Every other answer is this specification's own failure and stays loud. A success is the refusal
     * not happening at all - the regression these callers exist to catch. A server failure is the product
     * unable to answer, which says nothing about whether the request was acceptable; read as the expected
     * refusal it would let a server that fell over stand in for a gate that held, and the assertion on
     * the code would then be the only thing between a green run and a hole, on a body that carries no
     * code at all. A redirect is not a judgement either.
     */
    static Refusal interpretRefusal(int status, String body, String what) {
        if (status < 400) {
            throw new AssertionError(
                    "expected " + what + " to be refused, but the product did not refuse it: HTTP " + status
                            + " - " + body);
        }
        if (status >= 500) {
            throw new AssertionError(
                    "expected " + what + " to be refused, but the server failed instead: HTTP " + status
                            + " - " + body);
        }
        return new Refusal(status, codeOf(body));
    }

    /** Every connector id the online catalog answers with, registered rows and bundled ones alike. */
    List<String> connectorIds() {
        HttpResponse<String> response = send(authedGet("/api/connectors"));
        expect(response, 200, "list the online connector catalog");
        if (!(JsonReader.parse(response.body()) instanceof Map<?, ?> map)
                || !(map.get("connectors") instanceof List<?> connectors)) {
            throw new AssertionError("the catalog listing carried no connectors: " + response.body());
        }
        return connectors.stream()
                .map(each -> each instanceof Map<?, ?> row ? row.get("id") : null)
                .map(String::valueOf)
                .toList();
    }

    /** Discovers a source's model, which is what a target table is later derived from. */
    void discoverSchema(String resourceId, String connectorId, Map<String, Object> settings) {
        String body = JsonWriter.write(
                Map.of("id", resourceId, "connectorId", connectorId, "settings", settings));
        expect(send(authed("/api/connections:discover-schema", body)), 200, "discover the model of " + resourceId);
    }

    /**
     * The collections the read face lists for a declared source, each as the object it came down as.
     *
     * <p>Entries stay maps rather than becoming a record, and that is the point rather than laziness: what
     * this face promises about a thing nobody could answer is that the key is <em>absent</em>, not that it
     * carries an empty value. Decoding into a typed shape would give every absent key a null and erase
     * exactly the distinction a caller reads.
     */
    List<Map<String, Object>> collections(String sourceId) {
        HttpResponse<String> response = send(authedGet("/api/sources/" + sourceId + "/collections"));
        expect(response, 200, "list the collections of " + sourceId);
        return entriesOf(response.body(), "collections");
    }

    /** The refusal a listing was expected to be met with, read on the same terms every other one is. */
    Refusal collectionsExpectingRefusal(String sourceId) {
        HttpResponse<String> response = send(authedGet("/api/sources/" + sourceId + "/collections"));
        return interpretRefusal(response.statusCode(), response.body(), "listing the collections of " + sourceId);
    }

    /**
     * One preview read, answered whole: the rows, and whatever the face says around them.
     *
     * <p>The request travels as the caller wrote it rather than through named parameters, because several
     * of these specifications exist to send a field the shape does not have and watch it change nothing.
     * A typed argument list could not express that request at all.
     */
    Map<String, Object> find(String sourceId, String collection, Map<String, Object> request) {
        HttpResponse<String> response = send(authed(findPath(sourceId, collection), JsonWriter.write(request)));
        expect(response, 200, "read " + collection + " of " + sourceId);
        if (!(JsonReader.parse(response.body()) instanceof Map<?, ?> map)) {
            throw new AssertionError("a preview answer was not an object: " + response.body());
        }
        return asObject(map);
    }

    /** The refusal a read was expected to be met with, carrying the code the product named. */
    Refusal findExpectingRefusal(String sourceId, String collection, Map<String, Object> request) {
        HttpResponse<String> response = send(authed(findPath(sourceId, collection), JsonWriter.write(request)));
        return interpretRefusal(
                response.statusCode(), response.body(), "reading " + collection + " of " + sourceId);
    }

    /** What the face reports about one collection's size. */
    Map<String, Object> stats(String sourceId, String collection) {
        HttpResponse<String> response =
                send(authedGet("/api/sources/" + sourceId + "/collections/" + collection + "/stats"));
        expect(response, 200, "read the stats of " + collection + " of " + sourceId);
        if (!(JsonReader.parse(response.body()) instanceof Map<?, ?> map)) {
            throw new AssertionError("a stats answer was not an object: " + response.body());
        }
        return asObject(map);
    }

    /** The refusal a stats read was expected to be met with. */
    Refusal statsExpectingRefusal(String sourceId, String collection) {
        HttpResponse<String> response =
                send(authedGet("/api/sources/" + sourceId + "/collections/" + collection + "/stats"));
        return interpretRefusal(
                response.statusCode(), response.body(), "reading the stats of " + collection + " of " + sourceId);
    }

    private static String findPath(String sourceId, String collection) {
        return "/api/sources/" + sourceId + "/collections/" + collection + ":find";
    }

    /**
     * Opens a follow of a collection and collects the changes the product pushes down it.
     *
     * <p>Driven over the websocket rather than through the CLI, for the same reason every other read verb
     * here is: what is under test is the face, and a client in the middle would put its own reading of a
     * request between the caller and the answer. The filter travels in the handshake query as the very
     * JSON a one-shot read sends in its body, so "the same filter reached both faces" is a literal claim
     * rather than an approximate one.
     *
     * <p>The caller closes it. A follow holds a connector instance for as long as it is open, so one left
     * behind counts against the host's ceiling for the rest of the JVM.
     */
    Follow follow(String sourceId, String collection, Map<String, Object> filter) {
        String path = "/api/data-browser/" + sourceId + "/" + collection + "/tail";
        String query = filter == null
                ? ""
                : "?filter=" + URLEncoder.encode(JsonWriter.write(filter), StandardCharsets.UTF_8);
        URI address = URI.create(
                baseUrl.toString().replaceFirst("^http", "ws") + path + query);
        Follow follow = new Follow(address);
        try {
            http.newWebSocketBuilder()
                    .connectTimeout(TIMEOUT)
                    .header("Authorization", "Bearer " + requireCredential())
                    .buildAsync(address, follow)
                    .join();
        } catch (CompletionException refused) {
            throw new AssertionError("could not follow " + collection + " of " + sourceId, refused.getCause());
        }
        return follow;
    }

    /**
     * One open follow, and every change it has been sent.
     *
     * <p>Frames are kept whole and in arrival order. Order is what lets a caller assert that something was
     * <em>not</em> sent: within one follow the store's stream is ordered and skips nothing, so a change
     * the caller knows came last arriving is proof that every earlier one has already been delivered or
     * filtered out. Without that a "it never arrived" assertion is only ever "it had not arrived yet".
     */
    static final class Follow implements AutoCloseable, WebSocket.Listener {

        private final URI address;
        private final List<Map<String, Object>> frames = Collections.synchronizedList(new ArrayList<>());
        private final StringBuilder partial = new StringBuilder();
        private final AtomicReference<String> ended = new AtomicReference<>();

        private volatile WebSocket socket;

        private Follow(URI address) {
            this.address = address;
        }

        /** Every change delivered so far, oldest first. */
        List<Map<String, Object>> frames() {
            synchronized (frames) {
                return List.copyOf(frames);
            }
        }

        /**
         * Waits until a change satisfying the predicate has arrived, and answers with everything delivered
         * up to and including it.
         *
         * <p>Fails rather than returns short on timeout, and says what did arrive: a follow that is not
         * running at all and one that is running and filtering everything out look identical from here,
         * and only the frames that did come tell them apart.
         */
        List<Map<String, Object>> awaitFrame(Predicate<Map<String, Object>> wanted, Duration within, String what) {
            long deadline = System.nanoTime() + within.toNanos();
            while (System.nanoTime() - deadline < 0) {
                List<Map<String, Object>> delivered = frames();
                if (delivered.stream().anyMatch(wanted)) {
                    return delivered;
                }
                String closed = ended.get();
                if (closed != null) {
                    throw new AssertionError("the follow of " + address + " ended (" + closed
                            + ") before " + what + "; it had been sent " + delivered);
                }
                sleep();
            }
            throw new AssertionError("waited " + within + " for " + what + " on " + address
                    + ", and was sent " + frames());
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            socket = webSocket;
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String text = partial.toString();
                partial.setLength(0);
                if (!(JsonReader.parse(text) instanceof Map<?, ?> frame)) {
                    throw new AssertionError("a followed change was not an object: " + text);
                }
                frames.add(asObject(frame));
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            ended.compareAndSet(null, statusCode + " " + reason);
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            ended.compareAndSet(null, String.valueOf(error));
        }

        /**
         * Closes the follow, and never throws doing it.
         *
         * <p>This runs from a try-with-resources, so anything it threw would be added to whatever the
         * body was already failing with - and a peer that has gone away is the ordinary case here, not
         * a finding. The interesting failure is the assertion; this must not stand in front of it.
         */
        @Override
        public void close() {
            WebSocket open = socket;
            if (open == null) {
                return;
            }
            try {
                open.sendClose(WebSocket.NORMAL_CLOSURE, "done").join();
            } catch (RuntimeException alreadyGone) {
                open.abort();
            }
        }

        private static void sleep() {
            try {
                Thread.sleep(POLL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while following", e);
            }
        }
    }

    /** The list under one key of an answer, each element kept as the object it arrived as. */
    private static List<Map<String, Object>> entriesOf(String body, String key) {
        if (!(JsonReader.parse(body) instanceof Map<?, ?> map) || !(map.get(key) instanceof List<?> entries)) {
            throw new AssertionError("an answer carried no " + key + ": " + body);
        }
        List<Map<String, Object>> out = new ArrayList<>(entries.size());
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> row)) {
                throw new AssertionError("a " + key + " entry was not an object: " + body);
            }
            out.add(asObject(row));
        }
        return out;
    }

    /**
     * A decoded object as a map keyed by string. Keys absent on the wire stay absent here - nothing is
     * filled in - so a caller may assert that the product left a key out.
     */
    private static Map<String, Object> asObject(Map<?, ?> decoded) {
        Map<String, Object> out = new LinkedHashMap<>();
        decoded.forEach((key, value) -> out.put(String.valueOf(key), value));
        return out;
    }

    /**
     * Records a lifecycle intent. The verb's own spelling comes from the product's enum, so the wire
     * word cannot drift from the word the product accepts.
     */
    void lifecycle(String pipelineId, LifecycleVerb verb) {
        expect(send(authed("/api/pipelines/" + pipelineId + ":" + verb.id(), "")),
                200, verb.id() + " " + pipelineId);
    }

    /**
     * The published lifecycle state, or empty when the pipeline has published no observation yet.
     *
     * <p>Empty is a reading, not a failure. Between recording a start intent and the first convergence
     * pass there is no observation at all and the product says so with a coded refusal - so a caller that
     * took that for fatal could never wait for a pipeline to come up, which is the one thing waiting is
     * for. Answering with some state instead would be worse still: it would invent a reading.
     *
     * <p>Empty is weaker than it looks: this read is served from the published observations alone, so a
     * pipeline that was never applied answers exactly as one that is applied and not yet converged. A
     * caller cannot tell "still coming" from "never existed" here, and a wait for the second will spend
     * its whole bound before saying so.
     */
    Optional<PipelineState> state(String pipelineId) {
        HttpResponse<String> response = send(authedGet("/api/pipelines/" + pipelineId + "/status"));
        return interpretState(response.statusCode(), response.body(), pipelineId);
    }

    /**
     * The published error count, or empty when the pipeline has published no observation yet.
     *
     * <p>Empty is a reading and not a failure, on the same terms {@link #state} is: the metrics face answers
     * the product's own {@code monitor.no-observation} code for a pipeline no convergence pass has reached,
     * and a wait exists to sit through exactly that window.
     */
    Optional<Long> errorCount(String pipelineId) {
        HttpResponse<String> response = send(authedGet("/api/pipelines/" + pipelineId + "/metrics"));
        return interpretErrorCount(response.statusCode(), response.body(), pipelineId);
    }

    /**
     * The canonical code of the published failure, or empty when the pipeline has published none — it is
     * healthy, or no convergence pass has reached it yet.
     *
     * <p>Empty is a reading and not a failure, on the same terms {@link #state} is. The two emptinesses are
     * deliberately one here: a specification asserting a failure code is waiting for a run to die, and
     * "not dead yet" and "no observation yet" are both answered by waiting.
     */
    Optional<String> failureCode(String pipelineId) {
        HttpResponse<String> response = send(authedGet("/api/pipelines/" + pipelineId + "/status"));
        return interpretFailureCode(response.statusCode(), response.body(), pipelineId);
    }

    /**
     * What a status answer is allowed to say about a failure, read the way the two above are: only the
     * product's own {@code monitor.no-observation} code reads as "nothing published yet", every other refusal
     * stays loud. A healthy pipeline carries no failure field at all, which is the empty reading; a failure
     * present but missing its code is a regression of the status contract and is surfaced rather than waited
     * out.
     */
    static Optional<String> interpretFailureCode(int status, String body, String pipelineId) {
        if (status == 404 && MonitorError.NO_OBSERVATION.code().equals(codeOf(body))) {
            return Optional.empty();
        }
        if (status != 200) {
            throw new AssertionError(
                    "could not read the status of " + pipelineId + ": expected HTTP 200, got " + status
                            + " - " + body);
        }
        if (!(JsonReader.parse(body) instanceof Map<?, ?> map)) {
            throw new AssertionError("status answer was not an object: " + body);
        }
        if (!(map.get("failure") instanceof Map<?, ?> failure)) {
            return Optional.empty();
        }
        if (!(failure.get("code") instanceof String code)) {
            throw new AssertionError("status carried a failure with no code: " + body);
        }
        return Optional.of(code);
    }

    /**
     * What a status answer is allowed to mean. Only the product's own {@code monitor.no-observation} code
     * reads as "nothing published yet"; every other refusal stays loud, another code's 404 included. A rule
     * written on the status alone would let a route that 404s for its own reasons pass for a pipeline that
     * is merely slow to converge, and the specification would sit out its whole bound and then blame the
     * data. The code is the product's contract for exactly this distinction, so the code is what is read.
     */
    static Optional<PipelineState> interpretState(int status, String body, String pipelineId) {
        if (status == 404 && MonitorError.NO_OBSERVATION.code().equals(codeOf(body))) {
            return Optional.empty();
        }
        if (status != 200) {
            throw new AssertionError(
                    "could not read the status of " + pipelineId + ": expected HTTP 200, got " + status
                            + " - " + body);
        }
        if (!(JsonReader.parse(body) instanceof Map<?, ?> map) || !(map.get("state") instanceof String state)) {
            throw new AssertionError("status carried no state: " + body);
        }
        return Optional.of(PipelineState.valueOf(state));
    }

    /**
     * What a metrics answer is allowed to mean, read exactly the way a status answer is: only the product's
     * own {@code monitor.no-observation} code reads as "nothing published yet", and every other refusal stays
     * loud. A published observation always carries the errorCount metric - the runtime derives it from the
     * actual state - so a 200 that omits it is a regression of that contract, surfaced rather than waited out
     * as though the pipeline were merely slow to converge.
     */
    static Optional<Long> interpretErrorCount(int status, String body, String pipelineId) {
        if (status == 404 && MonitorError.NO_OBSERVATION.code().equals(codeOf(body))) {
            return Optional.empty();
        }
        if (status != 200) {
            throw new AssertionError(
                    "could not read the metrics of " + pipelineId + ": expected HTTP 200, got " + status
                            + " - " + body);
        }
        if (!(JsonReader.parse(body) instanceof Map<?, ?> map) || !(map.get("metrics") instanceof Map<?, ?> metrics)) {
            throw new AssertionError("metrics answer carried no metrics: " + body);
        }
        if (!(metrics.get("errorCount") instanceof Number errorCount)) {
            throw new AssertionError("metrics carried no errorCount: " + body);
        }
        return Optional.of(errorCount.longValue());
    }

    /**
     * The code a structured error body carries, or null for a body that is not one - a body that does not
     * parse included. A refusal can come from something that is not the product at all (an empty body, a
     * proxy's HTML), and the caller's job is to report that loudly with the pipeline and status named; it
     * cannot do that if reading the body for a code throws a parse error over the top of it.
     */
    private static String codeOf(String body) {
        try {
            return JsonReader.parse(body) instanceof Map<?, ?> map && map.get("code") instanceof String code
                    ? code
                    : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private HttpRequest get(String path) {
        return HttpRequest.newBuilder(baseUrl.resolve(path)).timeout(TIMEOUT).GET().build();
    }

    private HttpRequest authedGet(String path) {
        return HttpRequest.newBuilder(baseUrl.resolve(path))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + requireCredential())
                .GET()
                .build();
    }

    private HttpRequest post(String path, String body) {
        return HttpRequest.newBuilder(baseUrl.resolve(path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private HttpRequest authed(String path, String body) {
        return HttpRequest.newBuilder(baseUrl.resolve(path))
                .timeout(TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + requireCredential())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    private String requireCredential() {
        if (credential == null) {
            throw new IllegalStateException("no credential: log in before driving an authenticated verb");
        }
        return credential;
    }

    private HttpResponse<String> send(HttpRequest request) {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted while calling " + request.uri(), e);
        }
    }

    private static void expect(HttpResponse<String> response, int status, String what) {
        if (response.statusCode() != status) {
            throw new AssertionError(
                    "could not " + what + ": expected HTTP " + status + ", got " + response.statusCode()
                            + " - " + response.body());
        }
    }
}
