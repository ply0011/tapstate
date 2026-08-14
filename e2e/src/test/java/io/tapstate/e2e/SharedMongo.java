package io.tapstate.e2e;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.tapstate.adapters.mongostore.MongoStorePort;
import io.tapstate.testsupport.DockerGate;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * One replica set for every specification in the JVM.
 *
 * <p>A container per test class costs a start-up each time and buys nothing: runs stay independent
 * by taking a database of their own, not a daemon of their own. Ryuk reaps the container when the
 * JVM exits, so there is no stop to forget.
 */
final class SharedMongo {

    private static final DockerImageName IMAGE = DockerImageName.parse("mongo:7.0");

    /**
     * Read from the product's own constant rather than spelled again here: were it renamed and this a
     * copy, the drop would go on succeeding against a database nobody writes any more, and the runs it
     * is here to keep apart would quietly start sharing state again.
     */
    private static final String OPERATOR_STATE_DATABASE = MongoStorePort.NEST_STATE_DATABASE;

    private static MongoDBContainer container;

    private SharedMongo() {
    }

    /**
     * Forgets the operator state a previous run left on the shared daemon.
     *
     * <p>Runs stay independent here by taking a database of their own, and one store does not play
     * along: nest state is held in a database whose name is fixed rather than derived, so two runs on
     * one daemon find the same one. Inside it a pipeline's state is keyed by that pipeline's id - which
     * a bespoke test varies per tier, and a published example cannot, because its id is written in the
     * author's document. Two tiers of one example are therefore the same pipeline reading one state.
     *
     * <p>Left alone, the second tier resumes holding what the first tier's changes left behind, and
     * asserts its opening state against a document that already reflects the run's own later steps. It
     * fails on an assertion that is correct, which is the worst shape a harness fault can take.
     */
    static synchronized void forgetOperatorState() {
        if (container == null) {
            return;
        }
        try (MongoClient client = MongoClients.create(container.getReplicaSetUrl())) {
            client.getDatabase(OPERATOR_STATE_DATABASE).drop();
        }
    }

    /** The URL of a database on the shared replica set; the caller's name keeps its data its own. */
    static synchronized String replicaSetUrl(String database) {
        if (container == null) {
            DockerGate.require();
            MongoDBContainer starting = new MongoDBContainer(IMAGE);
            starting.start();
            container = starting;
        }
        return container.getReplicaSetUrl(database);
    }
}
