package io.tapstate.e2e;

import io.tapstate.testsupport.DockerGate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The PostgreSQL endpoint driver, checked against a real PostgreSQL and nothing else - no product, no
 * connector.
 *
 * <p>Checked the way its MySQL counterpart is: by writing rows with this driver and reading them back
 * over a connection this test opens itself, and by reading rows this driver did not write. A count taken
 * through the connector's own code would agree with the connector by construction and would keep
 * agreeing while nothing crossed the product at all.
 *
 * <p>Deliberately not a line-by-line copy of the MySQL witness. The two drivers answer the same contract,
 * and re-asserting all of it here would mostly re-test JDBC. What is worth a real database is the part
 * where the dialects diverge, because that is where this driver could be wrong while the other is right:
 * a positioned update or delete has to pick its rows with a subquery, identifiers fold to lower case
 * unless quoted, and a table is found in a schema rather than under the database's own name. Each of
 * those has a case below, and each would pass against a driver that got the dialect wrong in a way the
 * shared contract cases would not notice.
 *
 * <p>Needs Docker for the database, and nothing else - no connector jar.
 */
class PostgresEndpointsIT {

    private static final String TABLE = "orders";

    /** A database of this class's own on the shared server; nothing else writes in it. */
    private static Map<String, Object> settings;

    private final PostgresEndpoints endpoints = new PostgresEndpoints();

    @BeforeAll
    static void takeADatabase() {
        DockerGate.require();
        settings = SharedPostgres.settings("e2e_postgres_endpoints");
    }

    @AfterEach
    void releaseTheDriver() {
        endpoints.close();
    }

    // ---- the shared contract, in the shape every driver answers ---------------------------------

    @Test
    void seedsTheRowsThemselvesAndReadsOneBack() {
        endpoints.seed(at(), TABLE, List.of(
                Map.of("id", 1L, "name", "widget"),
                Map.of("id", 2L, "name", "gadget")));

        assertThat(endpoints.fetch(at(), TABLE, Map.of("id", 2L)))
                .hasValueSatisfying(row -> assertThat(row).containsEntry("name", "gadget"));
    }

    @Test
    void seedingWritesTheRowsNumberedFromOne() {
        endpoints.seed(at(), TABLE, SeedRows.generated(3));

        assertThat(rowsReadBackIndependently()).containsExactly("1,1", "2,2", "3,3");
    }

    @Test
    void countingReadsRowsThisDriverDidNotWrite() throws SQLException {
        endpoints.seed(at(), TABLE, SeedRows.generated(1));
        execute("INSERT INTO \"" + TABLE + "\" (id, seq) VALUES (99, 99)");

        // The point of the whole driver: it reports what the database holds, not what it put there.
        assertThat(endpoints.count(at(), TABLE)).isEqualTo(2L);
    }

    @Test
    void aTableNoOneHasCreatedYetCountsZeroRatherThanFailing() {
        // A specification waiting for a first write polls this before the product has created anything.
        // Letting "no such table" out instead would fail that wait on its first poll.
        assertThat(endpoints.count(at(), "a_table_nobody_made")).isZero();
        assertThat(endpoints.fetch(at(), "a_table_nobody_made", Map.of("id", 1L))).isEmpty();
    }

    @Test
    void insertingAppendsRowsAfterTheHighestIdTheTableHolds() {
        endpoints.seed(at(), TABLE, SeedRows.generated(2));

        endpoints.cdc(at(), TABLE, CdcOp.INSERT, 2);

        assertThat(rowsReadBackIndependently()).containsExactly("1,1", "2,2", "3,3", "4,4");
    }

    @Test
    void changingATableNoOneSeededRefusesRatherThanCreatingIt() {
        assertThatThrownBy(() -> endpoints.cdc(at(), "a_table_nobody_made", CdcOp.INSERT, 1))
                .isInstanceOf(EnvelopeException.class)
                .hasMessageContaining("has not been seeded");
    }

    // ---- where PostgreSQL differs, and where this driver could be wrong alone --------------------

    /**
     * MySQL spells a positioned update as {@code ORDER BY ... LIMIT} on the statement itself, which
     * PostgreSQL has no form for - this driver picks the ids in a subquery instead. A driver that kept
     * the MySQL spelling does not fail subtly here, it fails to parse, so this is the case that says the
     * translation happened at all.
     */
    @Test
    void updatingRewritesTheSequenceOfTheLowestIdsAndLeavesTheCountAlone() {
        endpoints.seed(at(), TABLE, SeedRows.generated(4));

        endpoints.cdc(at(), TABLE, CdcOp.UPDATE, 2);

        assertThat(endpoints.count(at(), TABLE)).isEqualTo(4L);
        assertThat(rowsReadBackIndependently()).containsExactly("1,-1", "2,-2", "3,3", "4,4");
    }

    @Test
    void deletingRemovesTheLowestIdsAndLowersTheCount() {
        endpoints.seed(at(), TABLE, SeedRows.generated(4));

        endpoints.cdc(at(), TABLE, CdcOp.DELETE, 2);

        assertThat(endpoints.count(at(), TABLE)).isEqualTo(2L);
        assertThat(rowsReadBackIndependently()).containsExactly("3,3", "4,4");
    }

    /**
     * The lowest ids present, not the numerically low ones. After a delete the ids no longer start at
     * one, and a subquery that read {@code id <= rows} rather than ordering would touch nothing here -
     * which is exactly the mistake the rewrite from MySQL's {@code LIMIT} form invites.
     */
    @Test
    void changesReachTheRowsThatAreActuallyLowestNotTheLowNumbers() {
        endpoints.seed(at(), TABLE, SeedRows.generated(4));
        endpoints.cdc(at(), TABLE, CdcOp.DELETE, 2);

        endpoints.cdc(at(), TABLE, CdcOp.UPDATE, 1);

        assertThat(rowsReadBackIndependently()).containsExactly("3,-3", "4,4");
    }

    @Test
    void insertingAfterADeleteContinuesFromTheHighestIdNotTheCount() {
        endpoints.seed(at(), TABLE, SeedRows.generated(3));
        endpoints.cdc(at(), TABLE, CdcOp.DELETE, 2);

        endpoints.cdc(at(), TABLE, CdcOp.INSERT, 1);

        // Continuing from the count would re-use id 3 and collide with the row still there.
        assertThat(rowsReadBackIndependently()).containsExactly("3,3", "4,4");
    }

    /**
     * An identifier keeps the case a specification wrote it in.
     *
     * <p>PostgreSQL folds an unquoted identifier to lower case, so a driver that did not quote would
     * create {@code Orders} as {@code orders} and then look it up under a name that does exist - and
     * every assertion would pass while a specification naming both tables saw one. Reading it back
     * through a quoted name this test writes itself is what makes that visible.
     */
    @Test
    void aTableNamedInMixedCaseKeepsThatCase() throws SQLException {
        // A name of this case's own, in both spellings. The shared table would not do: other cases in
        // this class seed it, so its folded name exists for reasons that have nothing to do with
        // quoting - and the assertion below would fail against a perfectly correct driver.
        endpoints.seed(at(), "MixedCase", SeedRows.generated(2));

        assertThat(endpoints.count(at(), "MixedCase")).isEqualTo(2L);
        assertThat(rowsOfQuoted("MixedCase")).containsExactly("1,1", "2,2");
        // The folded name is a different table, and nobody created it.
        assertThat(tableExists("mixedcase")).isFalse();
    }

    /**
     * The re-emission a stalled specification leans on, which this driver must implement rather than
     * inherit. Logical decoding is positional the way a binary log is, so a store that took the no-op
     * default would look like one with nothing to re-emit and would silently lose the only recovery a
     * stalled cross-engine case has.
     */
    @Test
    void reEmittingWritesEveryRowAgainUnchanged() {
        endpoints.seed(at(), TABLE, SeedRows.generated(3));

        endpoints.redeliver(at(), TABLE);

        assertThat(endpoints.count(at(), TABLE)).isEqualTo(3L);
        assertThat(rowsReadBackIndependently()).containsExactly("1,1", "2,2", "3,3");
    }

    @Test
    void reEmittingKeepsTheColumnsTheTableWasSeededWith() {
        endpoints.seed(at(), TABLE, List.of(
                Map.of("id", 1L, "name", "widget"),
                Map.of("id", 2L, "name", "gadget")));

        endpoints.redeliver(at(), TABLE);

        assertThat(endpoints.fetch(at(), TABLE, Map.of("id", 1L)))
                .hasValueSatisfying(row -> assertThat(row).containsEntry("name", "widget"));
        assertThat(endpoints.count(at(), TABLE)).isEqualTo(2L);
    }

    @Test
    void reEmittingLeavesTheConnectionCommittingAgain() {
        endpoints.seed(at(), TABLE, SeedRows.generated(2));

        endpoints.redeliver(at(), TABLE);

        // The re-emission runs in a transaction; a driver that did not give auto-commit back would leave
        // every later write of this run uncommitted, and the failure would land somewhere else entirely.
        endpoints.cdc(at(), TABLE, CdcOp.INSERT, 1);
        assertThat(rowsReadBackIndependently()).containsExactly("1,1", "2,2", "3,3");
    }

    @Test
    void reEmittingATableNoOneSeededDoesNothing() {
        endpoints.redeliver(at(), "a_table_nobody_made");

        assertThat(endpoints.count(at(), "a_table_nobody_made")).isZero();
    }

    @Test
    void insertingIntoATableOfAnotherShapeRefusesAndNamesTheShapes() {
        endpoints.seed(at(), TABLE, List.of(Map.of("id", 1L, "name", "widget")));

        assertThatThrownBy(() -> endpoints.cdc(at(), TABLE, CdcOp.INSERT, 1))
                .isInstanceOf(EnvelopeException.class)
                .hasMessageContaining("generated shape")
                .hasMessageContaining("name");
    }

    @Test
    void updatingATableWithNoSequenceRefusesAndNamesTheShape() {
        endpoints.seed(at(), TABLE, List.of(Map.of("id", 1L, "name", "widget")));

        assertThatThrownBy(() -> endpoints.cdc(at(), TABLE, CdcOp.UPDATE, 1))
                .isInstanceOf(EnvelopeException.class)
                .hasMessageContaining("seq");
    }

    /**
     * A store is a database of its own, so two of them share no tables.
     *
     * <p>Worth its own case here rather than inheriting the MySQL one: this driver finds a table by
     * schema, and every database's tables land in the same schema name. A lookup that matched on schema
     * alone against a shared connection would see the other store's table - so this is the case that
     * says the isolation is the database and not the schema string.
     */
    @Test
    void databasesTakenSeparatelyShareNoTables() {
        Map<String, Object> other = SharedPostgres.settings("e2e_postgres_endpoints_other");
        endpoints.seed(at(), TABLE, SeedRows.generated(2));

        assertThat(endpoints.count(new EndpointAddress("src_postgres", other), TABLE)).isZero();
        assertThat(endpoints.count(at(), TABLE)).isEqualTo(2L);
    }

    private static EndpointAddress at() {
        return new EndpointAddress("src_postgres", settings);
    }

    /**
     * Reads the table over a connection this test opens itself, so the driver cannot agree with itself:
     * a driver that reported what it meant to write rather than what the database holds would pass every
     * count assertion and fail here.
     */
    private List<String> rowsReadBackIndependently() {
        return rowsOfQuoted(TABLE);
    }

    private List<String> rowsOfQuoted(String table) {
        List<String> rows = new ArrayList<>();
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet results = statement.executeQuery(
                        "SELECT id, seq FROM \"" + table + "\" ORDER BY id")) {
            while (results.next()) {
                rows.add(results.getLong("id") + "," + results.getLong("seq"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("cannot read " + table + " back", e);
        }
        return rows;
    }

    private boolean tableExists(String table) throws SQLException {
        try (Connection connection = open();
                Statement statement = connection.createStatement();
                ResultSet results = statement.executeQuery(
                        "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' "
                                + "AND table_name = '" + table + "'")) {
            return results.next() && results.getLong(1) > 0;
        }
    }

    private void execute(String sql) throws SQLException {
        try (Connection connection = open(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static Connection open() throws SQLException {
        return SharedPostgres.connect(settings);
    }
}
