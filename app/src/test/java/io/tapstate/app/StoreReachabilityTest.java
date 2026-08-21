package io.tapstate.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tapstate.core.common.TapstateException;
import io.tapstate.spi.store.ConnectionTestItem;
import io.tapstate.spi.store.ConnectionTestResult;
import io.tapstate.spi.store.ConnectionTester;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * A pipeline that writes into an unreachable store must be refused where a person is watching, and the
 * refusal has to say which store and what the probe reported. The failures this covers all look the same
 * from outside if the check is missing: a pipeline that starts, reports itself running, and delivers
 * nothing.
 */
class StoreReachabilityTest {

    private static final Map<String, Object> SETTINGS = Map.of("uri", "mongodb://mongo:27017/views");

    private static ConnectionTestResult result(ConnectionTestResult.Outcome outcome,
            List<ConnectionTestItem> items) {
        return new ConnectionTestResult("views", "mongodb", outcome, items, 0L);
    }

    @Test
    void the_default_check_asks_nothing_and_refuses_nothing() {
        // The wiring default, so it must not turn a deployment with no prober into one that cannot start.
        assertThatCode(() -> StoreReachability.assumingReachable()
                .requireReachable("views", "mongodb", SETTINGS))
                .doesNotThrowAnyException();
    }

    @Test
    void a_store_that_answers_is_let_through() {
        ConnectionTester passing = config -> {
            assertThat(config.id()).isEqualTo("views");
            assertThat(config.connectorId()).isEqualTo("mongodb");
            assertThat(config.settings()).isEqualTo(SETTINGS);
            return result(ConnectionTestResult.Outcome.PASSED, List.of());
        };

        assertThatCode(() -> StoreReachability.probing(passing, Duration.ofSeconds(5))
                .requireReachable("views", "mongodb", SETTINGS))
                .doesNotThrowAnyException();
    }

    @Test
    void a_store_that_reports_failure_is_refused_with_the_code_and_what_it_said() {
        // The reason is carried rather than discarded: "unreachable" alone sends the reader to the server
        // log to find out what the connector actually objected to.
        ConnectionTester failing = config -> result(ConnectionTestResult.Outcome.FAILED,
                List.of(new ConnectionTestItem("connect", ConnectionTestItem.Status.FAILED,
                        "connection refused", null, null, null)));

        assertThatThrownBy(() -> StoreReachability.probing(failing, Duration.ofSeconds(5))
                .requireReachable("views", "mongodb", SETTINGS))
                .isInstanceOfSatisfying(TapstateException.class, e -> {
                    assertThat(e.code().code()).isEqualTo("actuation.view-store-unreachable");
                    assertThat(e.args()).containsEntry("store", "views");
                    assertThat((String) e.args().get("reason")).contains("connect", "connection refused");
                });
    }

    @Test
    void a_failure_with_nothing_to_say_still_says_something() {
        // A connector may report FAILED with no items at all. Rendering an empty reason would put an empty
        // string in front of an operator, which reads as a bug in the message rather than an answer.
        ConnectionTester silent = config -> result(ConnectionTestResult.Outcome.FAILED, List.of());

        assertThatThrownBy(() -> StoreReachability.probing(silent, Duration.ofSeconds(5))
                .requireReachable("views", "mongodb", SETTINGS))
                .isInstanceOfSatisfying(TapstateException.class, e ->
                        assertThat((String) e.args().get("reason")).isNotBlank());
    }

    @Test
    void a_connector_that_throws_is_the_same_answer_as_one_that_reports_failure() {
        // The only question asked here is whether this pipeline can write to the store, and a thrown
        // exception answers it exactly as FAILED does. Letting the raw exception out would escape the
        // error-code system on a path that is user-facing.
        ConnectionTester throwing = config -> {
            throw new IllegalStateException("driver blew up");
        };

        assertThatThrownBy(() -> StoreReachability.probing(throwing, Duration.ofSeconds(5))
                .requireReachable("views", "mongodb", SETTINGS))
                .isInstanceOfSatisfying(TapstateException.class, e -> {
                    assertThat(e.code().code()).isEqualTo("actuation.view-store-unreachable");
                    assertThat((String) e.args().get("reason")).contains("driver blew up");
                });
    }

    @Test
    void a_store_that_goes_quiet_is_refused_rather_than_waited_on() throws Exception {
        // The failure this check exists to remove is an indefinite start, so a probe with no deadline of
        // its own must not be able to reintroduce it. The latch keeps the fake hanging until the
        // assertion is done, which is what makes this test about the deadline rather than about luck.
        CountDownLatch release = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        ConnectionTester hanging = config -> {
            try {
                release.await();
            } catch (InterruptedException e) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            }
            return result(ConnectionTestResult.Outcome.PASSED, List.of());
        };

        long started = System.nanoTime();
        try {
            assertThatThrownBy(() -> StoreReachability.probing(hanging, Duration.ofMillis(150))
                    .requireReachable("views", "mongodb", SETTINGS))
                    .isInstanceOfSatisfying(TapstateException.class, e -> {
                        assertThat(e.code().code()).isEqualTo("actuation.view-store-unreachable");
                        assertThat((String) e.args().get("reason")).contains("150ms");
                    });
            // Bounded in fact, not only in the message: a check that returned the coded refusal only after
            // the probe finished would satisfy every assertion above and still park the start.
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                    .as("gave up near the deadline")
                    .isLessThan(Duration.ofSeconds(3));
        } finally {
            release.countDown();
        }

        // The probe is cancelled rather than left running: one that honours interruption stops.
        assertThat(interrupted.get()).as("the abandoned probe was interrupted").isTrue();
    }

    @Test
    void a_coded_refusal_from_the_probe_reaches_the_caller_unwrapped() {
        // Re-describing it would bury the store and reason the probe already named inside a second,
        // vaguer message built from an ExecutionException's toString.
        TapstateException coded = new TapstateException(ActuationError.VIEW_STORE_UNREACHABLE,
                Map.of("store", "views", "reason", "said so itself"), null);
        StoreReachability probe = (storeSourceId, connectorId, settings) -> {
            throw coded;
        };

        assertThatThrownBy(() -> StoreReachability.bounded(probe, Duration.ofSeconds(5))
                .requireReachable("views", "mongodb", SETTINGS))
                .isSameAs(coded);
    }

    @Test
    void the_probe_runs_on_a_thread_that_holds_nothing_shared() throws Exception {
        // A probe on a shared pool outlives the caller that gave up on it and competes with the rest of
        // the JVM. Asserting the thread is this call's own and a daemon is what keeps a later change from
        // quietly moving it onto a common pool.
        CountDownLatch saw = new CountDownLatch(1);
        AtomicBoolean daemon = new AtomicBoolean();
        StoreReachability probe = (storeSourceId, connectorId, settings) -> {
            daemon.set(Thread.currentThread().isDaemon());
            saw.countDown();
        };

        StoreReachability.bounded(probe, Duration.ofSeconds(5))
                .requireReachable("views", "mongodb", SETTINGS);

        assertThat(saw.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(daemon.get()).as("probe thread is a daemon").isTrue();
    }
}
