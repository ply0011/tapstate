package io.tapstate.e2e;

import io.tapstate.testsupport.DockerGate;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The hold, over a real database rather than an echo server.
 *
 * <p>{@link StreamGateTest} proves the relay behaves; it cannot prove that a real client protocol
 * survives being held, and that is the claim everything above this rests on. A database conversation
 * is not one request and one reply - there are handshakes, keepalives and, for a change stream, a
 * long-lived connection the server pushes down - so a relay that works on lines of text can still be
 * the thing that breaks a connector, and it would break it far away from here.
 *
 * <p>Two readings, and the second is the one worth having:
 *
 * <ul>
 *   <li><b>A reader dialling the published address is held.</b> That is what the product does.
 *   <li><b>The harness dialling the store's own address is not.</b> That is what makes a hold usable
 *       at all: a specification holds a stream in order to write rows into it while it is held, and a
 *       harness that blocked on its own hold could never write them. A gate published to both sides
 *       would pass the first reading and deadlock every specification that ever used it.
 * </ul>
 *
 * <p>Bounded rather than instantaneous, for the reason {@link StreamGateTest} gives: "does not
 * complete" is only observable as "has not completed yet".
 */
class StreamGateOverARealStoreIT {

    /** Long enough that an unheld query would have finished many times over. */
    private static final int HELD_BOUND_SECONDS = 3;

    /** Generous: this one is waited out only when the gate is working. */
    private static final int RELEASED_BOUND_SECONDS = 60;

    @BeforeAll
    static void requireDocker() {
        DockerGate.require();
    }

    @Test
    void holdsAReaderOnThePublishedAddressWhileLettingTheHarnessReachTheStore() throws Exception {
        Map<String, DatabaseRequest> asked = Map.of("src", new DatabaseRequest(DatabaseKind.POSTGRES));

        try (ProvisionedStores stores = ProvisionedStores.provision(asked, "gated_postgres")) {
            Endpoints driver = stores.driversByConnector().get("postgres");
            EndpointAddress published = addressFrom(stores.environment(), "SRC", "src_postgres");

            driver.seed(published, "orders", SeedRows.generated(3));
            assertThat(driver.count(published, "orders"))
                    .as("an open gate is a relay: a real driver reaches a real store through it")
                    .isEqualTo(3L);

            stores.driveStream(published, StreamVerb.PAUSE);

            CompletableFuture<Long> heldRead =
                    CompletableFuture.supplyAsync(() -> driver.count(published, "orders"));
            assertThatThrownBy(() -> heldRead.get(HELD_BOUND_SECONDS, TimeUnit.SECONDS))
                    .as("held, a reader on the published address gets nothing back - this is what the "
                            + "product experiences, and a gate that let it through would make every "
                            + "specification about arrival order measure the ordinary order instead")
                    .isInstanceOf(TimeoutException.class);

            // The half a specification actually depends on: rows written into a held source. Bounded,
            // because the failure it guards against is not a wrong answer but no answer - a harness
            // dialling through its own hold blocks forever, and an unbounded write would turn that into
            // a run that never ends rather than a test that names the fault.
            EndpointAddress asTheHarnessDialsIt = stores.behindTheGate(published);
            CompletableFuture<Long> writeWhileHeld = CompletableFuture.supplyAsync(() -> {
                driver.seed(asTheHarnessDialsIt, "orders", SeedRows.generated(5));
                return stores.count("src", "orders");
            });
            assertThat(writeWhileHeld)
                    .as("the store keeps accepting writes while its stream is held, over the handle this "
                            + "run kept for itself - a harness that dialled through the gate would block "
                            + "here and every specification using a hold would hang")
                    .succeedsWithin(RELEASED_BOUND_SECONDS, TimeUnit.SECONDS)
                    .isEqualTo(5L);

            stores.driveStream(published, StreamVerb.RESUME);
            assertThat(heldRead.get(RELEASED_BOUND_SECONDS, TimeUnit.SECONDS))
                    .as("releasing completes the conversation that was held rather than abandoning it: "
                            + "the reader gets its answer, not an error")
                    .isEqualTo(5L);
        }
    }

    @Test
    void releasesEveryHoldWhenTheRunThatMadeThemEnds() throws Exception {
        Map<String, DatabaseRequest> asked = Map.of("src", new DatabaseRequest(DatabaseKind.POSTGRES));
        EndpointAddress published;
        Endpoints driver;

        try (ProvisionedStores stores = ProvisionedStores.provision(asked, "gate_closes")) {
            driver = stores.driversByConnector().get("postgres");
            published = addressFrom(stores.environment(), "SRC", "src_postgres");
            driver.seed(published, "orders", SeedRows.generated(1));
            stores.driveStream(published, StreamVerb.PAUSE);
        }

        // The gate is gone with the run, so the address it published no longer answers at all. What must
        // not happen is a held gate outliving its run and parking whatever dials it next forever.
        CompletableFuture<Long> afterTheRun =
                CompletableFuture.supplyAsync(() -> driver.count(published, "orders"));
        assertThatThrownBy(() -> afterTheRun.get(RELEASED_BOUND_SECONDS, TimeUnit.SECONDS))
                .as("a closed gate refuses rather than holds")
                .isInstanceOf(ExecutionException.class);
    }

    /** The address a resource writes, assembled from the published references exactly as it would. */
    private static EndpointAddress addressFrom(Map<String, String> environment, String prefix, String id) {
        Map<String, Object> settings = new LinkedHashMap<>();
        for (String setting : new String[] {"host", "port", "database", "username", "password"}) {
            String value = environment.get(prefix + "_" + setting.toUpperCase(Locale.ROOT));
            if (value != null) {
                settings.put(setting, value);
            }
        }
        return new EndpointAddress(id, settings);
    }
}
