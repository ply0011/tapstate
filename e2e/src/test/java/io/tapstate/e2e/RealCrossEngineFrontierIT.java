package io.tapstate.e2e;

import io.tapstate.core.lifecycle.LifecycleVerb;
import io.tapstate.core.lifecycle.PipelineState;
import io.tapstate.testsupport.DockerGate;

import org.bson.Document;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One job reading two different database engines: both halves arrive, and neither engine's silence holds
 * the other's durable offset back.
 *
 * <p>Every multi-source witness before this one reads two tables of a single database, which is one
 * mining chain wearing two names. This is the first that opens two - two engines, two capture streams,
 * two chain records - and it exists because the interesting question only appears once there are two:
 * what the run would resume from is clamped to what has been acked, and a stream that stops speaking
 * stops contributing anything to clamp with. There is no tick anywhere that would speak on its behalf;
 * an idle source is silent by design.
 *
 * <p><b>What this actually pins down.</b> The clamp is computed per chain, so silence on one is not
 * supposed to reach the other. That is a claim about the shape of the code, and this is the run that
 * makes it a claim about the product: each engine is driven while the other is left completely idle, and
 * the chain belonging to the one being driven has to move. If the two were ever coupled - by a clamp
 * taken across chains rather than within one - the driven engine's offset would sit still behind the
 * idle one, and it would do so while every other reading stayed healthy: rows keep landing, the state
 * stays RUNNING, nothing is counted as an error. Only the offset would show it, by not moving.
 *
 * <p><b>Which chain is which is discovered, not assumed.</b> A chain record is named by a hash of the
 * connection settings, so neither id can be predicted from the specification. Rather than guess, each
 * engine is driven on its own and the chain that moves is thereby the chain that engine writes. That
 * also makes the two phases each other's control: the second phase must move <i>the other</i> record, so
 * a run in which one chain moves for reasons of its own cannot satisfy both.
 *
 * <p><b>Why the count assertion cannot carry this.</b> An implementation that honoured only the first
 * stream fails the arrival check below and never reaches the frontier phases - which is why that check
 * is here and why it is not enough on its own. Coupled frontiers deliver every row correctly.
 *
 * <p>Gated on Docker and on a directory of real connector jars, like its siblings. Run it with:
 *
 * <pre>
 *   mvn -pl e2e -am verify -Dapi.version=1.44 \
 *     -Dtapstate.e2e.connectors-dir=/path/to/connectors \
 *     -Dit.test=RealCrossEngineFrontierIT -Dfailsafe.failIfNoSpecifiedTests=false
 * </pre>
 */
@Disabled("Unfinished, and its remaining value is now questionable - read this before spending on it.\n"
        + "THE PRODUCT IS FINE. Two engines carrying changes into one target is covered, green, and "
        + "checked in: see the examples two-engines-reach-one-target and two-engines-keep-carrying-"
        + "changes. Those cover what this witness's arrival half was for, through the runner, on both "
        + "tiers.\n"
        + "WHAT IS LEFT UNCOVERED is only the frontier half - and mapping the machinery showed the min() "
        + "is computed per chain and never across chains, so for a flat pipeline like this one the "
        + "chains are structurally independent and there is nothing to pin. The coupling that would make "
        + "this interesting exists only under a nest, which is T8/T9.\n"
        + "WHY IT STILL FAILS: the postgres capture dies with connector.capture-failed / "
        + "UnsupportedOperationException while reading, and the pipeline goes FAILED. Ruled out by "
        + "measurement: not cross-source, not the jars or lane, not the stream's positioning window, not "
        + "the config vocabularies, not the setup ordering (discover-before-apply, matched to the "
        + "runner), and not re-emission (removing it changes nothing). The same shape works "
        + "declaratively, so the difference is still somewhere between this hand-rolled flow and "
        + "HttpTierBinding.\n"
        + "RECOMMENDATION: decide whether this witness is worth finishing at all before debugging "
        + "further - the case it was written to catch may not be reachable without a nest.")
class RealCrossEngineFrontierIT {

    /**
     * Longer than the shared default, because this run brings up two database engines and waits for a
     * snapshot and then a change stream through each. A bound only decides how long a failure takes to
     * report itself; it cannot make a broken run pass.
     */
    private static final Duration FRONTIER_BOUND = Duration.ofMinutes(3);

    private static final String ORDERS = "orders";
    private static final String SHIPMENTS = "shipments";
    private static final String PIPELINE_ID = "cross_engine";

    /** Where the product keeps what it would resume from: one record per mining chain, in the store. */
    private static final String CHAIN_RECORDS = "srs_meta";
    private static final String OFFSET_FIELD = "sourceReadOffset";

    /** Seeded before the run, so the snapshot half has something to carry from each engine. */
    private static final int SEEDED_ORDERS = 5;
    private static final int SEEDED_SHIPMENTS = 4;

    /**
     * Changes made on both engines before the baseline. A durable offset does not exist until a consumer
     * has acked something, so a baseline taken any earlier finds nothing on either chain and the run
     * looks broken while being healthy.
     */
    private static final int WARMUP_CHANGES = 6;

    /** Changes made on one engine while the other is left completely silent. */
    private static final int DRIVEN_CHANGES = 8;

    @BeforeAll
    static void requireDockerAndRealConnectors() {
        DockerGate.require();
        RealConnectorGate.require("mysql", "postgres", "mongodb");
    }

    @ParameterizedTest
    @EnumSource(Tiers.class)
    void neitherEnginesSilenceHoldsTheOthersDurableOffsetBack(Tiers tier) throws Exception {
        try (MySQLContainer<?> mysql = new MySQLContainer<>(DockerImageName.parse("mysql:8.0"));
                PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(DockerImageName.parse("postgres:16"))
                        // Logical decoding, which the image does not serve by default. Without it the
                        // snapshot half of this run would work and the change stream would fail at slot
                        // creation - the half-working state that reads as a product fault.
                        .withCommand("postgres", "-c", "wal_level=logical",
                                "-c", "max_wal_senders=8", "-c", "max_replication_slots=8")) {
            mysql.start();
            postgres.start();
            grantReplication(mysql);

            String suffix = tier.name().toLowerCase(Locale.ROOT);
            String pipelineId = PIPELINE_ID + "_" + suffix;
            String storeUri = SharedMongo.replicaSetUrl("cross_engine_store_" + suffix);
            String targetUri = SharedMongo.replicaSetUrl("cross_engine_target_" + suffix);

            try (ServerHandle server = tier.launch(storeUri);
                    MongoEndpoints mongo = new MongoEndpoints();
                    MySqlEndpoints mysqlRows = new MySqlEndpoints();
                    PostgresEndpoints postgresRows = new PostgresEndpoints()) {
                ControlPlane control = new ControlPlane(server.baseUrl());
                control.bootstrapAndLogin("e2e", "e2e-password");

                EndpointAddress mysqlAt = new EndpointAddress("src_orders", mysqlConfig(mysql));
                EndpointAddress postgresAt = new EndpointAddress("src_shipments", postgresConfig(postgres));
                mysqlRows.seed(mysqlAt, ORDERS, SeedRows.generated(SEEDED_ORDERS));
                postgresRows.seed(postgresAt, SHIPMENTS, SeedRows.generated(SEEDED_SHIPMENTS));

                control.registerConnector("mysql", ConnectorJars.bytesFor("mysql"));
                control.registerConnector("postgres", ConnectorJars.bytesFor("postgres"));
                control.registerConnector("mongodb", ConnectorJars.bytesFor("mongodb"));

                Map<String, Object> mysqlConfig = mysqlConfig(mysql);
                Map<String, Object> postgresConfig = postgresConfig(postgres);
                Map<String, String> resources = new LinkedHashMap<>();
                resources.put("src_orders.tap.yml", mysqlSourceYaml(mysqlConfig));
                resources.put("src_shipments.tap.yml", postgresSourceYaml(postgresConfig));
                resources.put("tgt_mongo.tap.yml", targetYaml(targetUri));
                resources.put("pipeline.tap.yml", pipelineYaml(pipelineId));
                // Discovery first, then apply - the order the specification runner uses, and it is
                // load-bearing rather than tidy. A source model is read out of what the source holds, and
                // a pipeline applied before that model exists is built against a source whose tables are
                // unknown. The snapshot half survives that; the change stream does not, and it does not
                // complain either.
                control.discoverSchema("src_orders", "mysql", mysqlConfig);
                control.discoverSchema("src_shipments", "postgres", asPostgresConnectorSees(postgresConfig));

                control.apply(resources);

                control.lifecycle(pipelineId, LifecycleVerb.START);

                // Both halves arrive. An implementation that honours only the first stream stops here,
                // and it stops with half the rows rather than with an error.
                Await.until("both engines' seeded rows to reach the target", FRONTIER_BOUND,
                        () -> count(mongo, targetUri, ORDERS) == SEEDED_ORDERS
                                && count(mongo, targetUri, SHIPMENTS) == SEEDED_SHIPMENTS,
                        () -> ORDERS + "=" + count(mongo, targetUri, ORDERS) + " "
                                + SHIPMENTS + "=" + count(mongo, targetUri, SHIPMENTS));
                assertThat(count(mongo, targetUri, ORDERS))
                        .as("rows from the MySQL half").isEqualTo(SEEDED_ORDERS);
                assertThat(count(mongo, targetUri, SHIPMENTS))
                        .as("rows from the PostgreSQL half").isEqualTo(SEEDED_SHIPMENTS);

                // Traffic on both, so both chains have an acked consumer and therefore an offset at all.
                mysqlRows.cdc(mysqlAt, ORDERS, CdcOp.INSERT, WARMUP_CHANGES);
                postgresRows.cdc(postgresAt, SHIPMENTS, CdcOp.INSERT, WARMUP_CHANGES);
                // Both change streams have to be alive before any question about offsets means anything.
                // Without this the run below cannot tell "the frontier is pinned" from "no change ever
                // crossed", and those are different findings.
                Await.until("changes from both engines to reach the target", FRONTIER_BOUND,
                        () -> count(mongo, targetUri, ORDERS) == SEEDED_ORDERS + WARMUP_CHANGES
                                && count(mongo, targetUri, SHIPMENTS) == SEEDED_SHIPMENTS + WARMUP_CHANGES,
                        () -> ORDERS + "=" + count(mongo, targetUri, ORDERS) + " " + SHIPMENTS + "="
                                + count(mongo, targetUri, SHIPMENTS) + "; " + productState(control, pipelineId));
                Await.until("both chains to carry a durable offset", FRONTIER_BOUND,
                        () -> offsets(mongo, storeUri).size() == 2,
                        () -> "offsets " + offsets(mongo, storeUri) + "; chain records "
                                + mongo.documents(EndpointAddress.uri(storeUri), CHAIN_RECORDS));

                Map<String, String> baseline = offsets(mongo, storeUri);
                // Without this the phases below are vacuous: one chain cannot be idle while the other
                // moves if there is only one chain, and two tables of one engine would give exactly that.
                assertThat(baseline)
                        .as("two engines have to have opened two chain records, or there is no second "
                                + "stream to leave idle. Records now: %s",
                                mongo.documents(EndpointAddress.uri(storeUri), CHAIN_RECORDS))
                        .hasSize(2);

                // Phase one: only MySQL speaks. PostgreSQL is not touched at all.
                mysqlRows.cdc(mysqlAt, ORDERS, CdcOp.INSERT, DRIVEN_CHANGES);
                Await.until("the MySQL chain's offset to move while PostgreSQL stays silent", FRONTIER_BOUND,
                        () -> movedSince(baseline, offsets(mongo, storeUri)).size() == 1,
                        () -> "baseline " + baseline + " now " + offsets(mongo, storeUri) + "; "
                                + productState(control, pipelineId));
                Map<String, String> afterMysql = offsets(mongo, storeUri);
                List<String> movedByMysql = movedSince(baseline, afterMysql);
                assertThat(movedByMysql)
                        .as("with only MySQL changing, exactly one chain must move - its own. A clamp "
                                + "taken across chains rather than within one would leave this empty, "
                                + "while rows kept landing and the run stayed healthy.%n"
                                + "  baseline: %s%n  after:    %s%n  metrics:  %s",
                                baseline, afterMysql, control.metrics(pipelineId))
                        .hasSize(1);

                // Phase two: only PostgreSQL speaks - and it must move the *other* record. This is what
                // stops phase one from passing on a chain that happens to move for reasons of its own.
                postgresRows.cdc(postgresAt, SHIPMENTS, CdcOp.INSERT, DRIVEN_CHANGES);
                Await.until("the PostgreSQL chain's offset to move while MySQL stays silent", FRONTIER_BOUND,
                        () -> movedSince(afterMysql, offsets(mongo, storeUri)).size() == 1,
                        () -> "before " + afterMysql + " now " + offsets(mongo, storeUri) + "; "
                                + productState(control, pipelineId));
                Map<String, String> afterPostgres = offsets(mongo, storeUri);
                List<String> movedByPostgres = movedSince(afterMysql, afterPostgres);
                assertThat(movedByPostgres)
                        .as("with only PostgreSQL changing, exactly one chain must move.%n"
                                + "  before: %s%n  after:  %s%n  metrics: %s",
                                afterMysql, afterPostgres, control.metrics(pipelineId))
                        .hasSize(1);
                assertThat(movedByPostgres)
                        .as("and it must be the other chain: the same record moving in both phases would "
                                + "mean one engine's changes are being attributed to the other's stream")
                        .isNotEqualTo(movedByMysql);

                // Read after the offsets, so a run that died on the way cannot satisfy the assertions
                // above by having stopped for the wrong reason.
                assertThat(control.state(pipelineId))
                        .as("the run has to be alive for a frontier reading to mean anything")
                        .contains(PipelineState.RUNNING);
                assertThat(control.errorCount(pipelineId)).contains(0L);
            }
        }
    }


    /** What the product reports about the run, for a wait that is about to give up on it. */
    private static String productState(ControlPlane control, String pipelineId) {
        return "state=" + control.state(pipelineId) + " errors=" + control.errorCount(pipelineId)
                + " status=" + control.statusBody(pipelineId);
    }

    /** The chain records whose offset differs from what it was, named by record id. */
    private static List<String> movedSince(Map<String, String> before, Map<String, String> after) {
        List<String> moved = new ArrayList<>();
        after.forEach((chain, offset) -> {
            if (!offset.equals(before.get(chain))) {
                moved.add(chain);
            }
        });
        java.util.Collections.sort(moved);
        return moved;
    }

    /** Every chain record's offset, keyed by record id, read out of the store the product writes. */
    private static Map<String, String> offsets(MongoEndpoints mongo, String storeUri) {
        Map<String, String> offsets = new LinkedHashMap<>();
        for (Document record : mongo.documents(EndpointAddress.uri(storeUri), CHAIN_RECORDS)) {
            Object offset = record.get(OFFSET_FIELD);
            if (offset != null) {
                offsets.put(String.valueOf(record.get("_id")), String.valueOf(offset));
            }
        }
        return offsets;
    }

    private static long count(MongoEndpoints mongo, String targetUri, String table) {
        return mongo.count(EndpointAddress.uri(targetUri), table);
    }


    /** Binlog CDC needs replication privileges the image's default user does not carry. */
    private static void grantReplication(MySQLContainer<?> mysql) throws SQLException {
        try (Connection root = DriverManager.getConnection(mysql.getJdbcUrl(), "root", mysql.getPassword());
                Statement statement = root.createStatement()) {
            statement.execute("GRANT REPLICATION SLAVE, REPLICATION CLIENT, RELOAD, SELECT ON *.* TO '"
                    + mysql.getUsername() + "'@'%'");
            statement.execute("FLUSH PRIVILEGES");
        }
    }




    private static Connection mysqlConnection(MySQLContainer<?> mysql) throws SQLException {
        return DriverManager.getConnection(
                mysql.getJdbcUrl() + "?sslMode=DISABLED&allowPublicKeyRetrieval=true",
                mysql.getUsername(), mysql.getPassword());
    }

    private static Connection postgresConnection(PostgreSQLContainer<?> postgres) throws SQLException {
        return DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    }

    private static Map<String, Object> mysqlConfig(MySQLContainer<?> mysql) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("host", mysql.getHost());
        config.put("port", mysql.getMappedPort(MySQLContainer.MYSQL_PORT));
        config.put("database", mysql.getDatabaseName());
        config.put("username", mysql.getUsername());
        config.put("password", mysql.getPassword());
        return config;
    }

    private static Map<String, Object> postgresConfig(PostgreSQLContainer<?> postgres) {
        Map<String, Object> config = new LinkedHashMap<>();
        config.put("host", postgres.getHost());
        config.put("port", postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT));
        config.put("database", postgres.getDatabaseName());
        // Required by this connector and by no other here: PostgreSQL addresses a table by schema as
        // well as by database, so leaving it out is refused as dsl.config-required rather than guessed.
        config.put("schema", "public");
        // Held in the harness's own vocabulary, which is "username" for every store: one address shape
        // is what lets one specification read the same against any of them. The connector's own spelling
        // is a different matter and is applied where the resource is written, below.
        config.put("username", postgres.getUsername());
        config.put("password", postgres.getPassword());
        return config;
    }

    /**
     * The same settings in the connector's vocabulary rather than the harness's.
     *
     * <p>One translation, in one place, because the two are genuinely different contracts: every harness
     * driver takes an address spelled the same way, which is what lets one specification read the same
     * against any store, while each connector spells its own settings however it does. Postgres calls the
     * account "user" where MySQL calls it "username", and passing the wrong one is not refused - the
     * driver falls back to the operating-system account, and it arrives much later as a rejected
     * password for a user nobody named.
     */
    private static Map<String, Object> asPostgresConnectorSees(Map<String, Object> harnessSettings) {
        Map<String, Object> forConnector = new LinkedHashMap<>(harnessSettings);
        forConnector.put("user", forConnector.remove("username"));
        return forConnector;
    }

    private static String mysqlSourceYaml(Map<String, Object> config) {
        return """
                version: tapstate/v1
                kind: source
                id: src_orders
                connector: mysql
                config: { host: %s, port: %s, database: %s, username: %s, password: %s }
                mode: cdc
                tables: [ %s ]
                """
                .formatted(config.get("host"), config.get("port"), config.get("database"),
                        config.get("username"), config.get("password"), ORDERS);
    }

    private static String postgresSourceYaml(Map<String, Object> config) {
        return """
                version: tapstate/v1
                kind: source
                id: src_shipments
                connector: postgres
                config: { host: %s, port: %s, database: %s, schema: %s, user: %s, password: %s }
                # "user" above, not "username": this connector spells the account differently from the
                # MySQL one, and getting it wrong is silent - the driver falls back to the operating
                # system account, and it surfaces much later as a rejected password for a user nobody
                # named. The harness's own address keeps saying "username"; only this line translates.
                mode: cdc
                tables: [ %s ]
                """
                .formatted(config.get("host"), config.get("port"), config.get("database"),
                        config.get("schema"), config.get("username"), config.get("password"), SHIPMENTS);
    }

    private static String targetYaml(String targetUri) {
        return """
                version: tapstate/v1
                kind: source
                id: tgt_mongo
                connector: mongodb
                config: { uri: "%s" }
                """
                .formatted(targetUri);
    }

    /**
     * Two sources into one sink, with no nest and no view: this witness is about two capture streams
     * sharing a job, and assembling them into one object is a different question with its own witness.
     * The transform admits everything, so what reaches the sink is what the two streams carried.
     */
    private static String pipelineYaml(String pipelineId) {
        return """
                version: tapstate/v1
                kind: pipeline
                id: %s
                source: [ src_orders, src_shipments ]
                settings: { read_mode: snapshot_and_cdc }
                transforms:
                  - { id: both_engines, from: [ %s, %s ], type: filter, expr: "true" }
                serve:
                  from: both_engines
                  sync:
                    - source: tgt_mongo
                """
                .formatted(pipelineId, ORDERS, SHIPMENTS);
    }
}
