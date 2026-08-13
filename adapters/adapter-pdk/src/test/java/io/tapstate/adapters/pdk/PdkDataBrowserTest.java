package io.tapstate.adapters.pdk;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.tapstate.core.common.TapstateException;
import io.tapstate.spi.store.ConnectionConfig;
import io.tapstate.spi.store.DataBrowserQuery;
import io.tapstate.spi.store.DataBrowserSort;
import io.tapstate.spi.store.DataBrowserSort.Direction;
import io.tapstate.spi.store.DataBrowserTableInfo;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The data-browser PDK bridge: {@link PdkDataBrowser} driving the three read-face functions a connector
 * may register — {@code getTableNames}, {@code getTableInfo} and {@code executeCommand}. Synthetic
 * connectors compiled at test time prove the drive and the coded-error paths without a real connector
 * jar or the PDK runtime; each is shaped after the behaviour a real connector actually exhibits, so a
 * drive that holds here holds there.
 */
class PdkDataBrowserTest {

    private final List<PdkDataBrowser> readers = new ArrayList<>();

    @AfterEach
    void closeReaders() {
        readers.forEach(PdkDataBrowser::close);
        System.clearProperty("synthetic.marker");
    }

    /** A reader over a provisioner that hands back one fixed connector ref, whatever id is asked for. */
    private PdkDataBrowser reader(Path jar, String className) {
        return reader(jar, className, ConnectorInstancePool.DEFAULTS);
    }

    private PdkDataBrowser reader(Path jar, String className, ConnectorInstancePool.Limits limits) {
        ConnectorRef ref = new ConnectorRef(List.of(jar), className, "2.0.8", null);
        PdkDataBrowser reader = new PdkDataBrowser(connectorId -> ref, limits, Clock.systemUTC());
        readers.add(reader);
        return reader;
    }

    private static ConnectionConfig config() {
        return new ConnectionConfig("conn-1", "demo", Map.of());
    }

    private static ConnectionConfig config(String database) {
        return new ConnectionConfig("conn-1", "demo", Map.of("database", database));
    }

    /**
     * Points the lifecycle-recording connector at a file in {@code dir} and hands it back. Reading it
     * is how a test counts drives of a connector that gets a fresh class loader every time it is
     * opened — which is precisely what makes an in-connector counter useless here.
     */
    private static Path marker(Path dir) {
        Path marker = dir.resolve("lifecycle.log");
        System.setProperty("synthetic.marker", marker.toString());
        return marker;
    }

    private static List<String> drives(Path marker) throws IOException {
        return Files.exists(marker) ? Files.readAllLines(marker) : List.of();
    }

    // ---- the pooled instance ---------------------------------------------------------------------

    @Test
    void reusesOneConnectorAcrossReadsOfTheSameConnection(@TempDir Path dir) throws IOException {
        // Opening is a class loader, a linked jar and a constructed connector, and initializing is what
        // builds the driver's own connection pool behind it. Paying that per query is what rules out any
        // caller that reads on a timer.
        PdkDataBrowser reader = reader(Synthetic.lifecycleRecordingSource(dir), "synthetic.LifecycleRecording");
        Path marker = marker(dir);

        reader.collections(config());
        reader.collections(config());

        assertThat(drives(marker)).containsExactly("init");
    }

    @Test
    void opensASecondConnectorOnceTheConnectionSettingsChange(@TempDir Path dir) throws IOException {
        // The instance holds the settings it was opened with, so an applied change has to reach the next
        // read. Kept across it, the read answers from the old database and reports nothing wrong.
        PdkDataBrowser reader = reader(Synthetic.lifecycleRecordingSource(dir), "synthetic.LifecycleRecording");
        Path marker = marker(dir);

        reader.collections(config("one"));
        reader.collections(config("two"));

        assertThat(drives(marker)).containsExactly("init", "init");
    }

    @Test
    void stopsThePooledConnectorWhenTheReaderCloses(@TempDir Path dir) throws IOException {
        // A pooled instance is live: it holds its class loader open and its driver's connections with it,
        // so shutting the face down has to hand them back rather than drop the reference.
        PdkDataBrowser reader = reader(Synthetic.lifecycleRecordingSource(dir), "synthetic.LifecycleRecording");
        Path marker = marker(dir);
        reader.collections(config());

        reader.close();

        assertThat(drives(marker)).containsExactly("init", "stop");
    }

    @Test
    void stopsAConnectorThatHasSatIdleWithoutAnyFurtherReads(@TempDir Path dir) throws Exception {
        // Eviction has to happen on its own. Checked only when the next read arrives, an idle instance on
        // a face nobody is using holds its connections for as long as nobody uses it - which is exactly
        // when they should have been given back.
        ConnectorInstancePool.Limits limits = ConnectorInstancePool.DEFAULTS.withIdle(Duration.ofMillis(50));
        PdkDataBrowser reader = reader(Synthetic.lifecycleRecordingSource(dir), "synthetic.LifecycleRecording", limits);
        Path marker = marker(dir);
        reader.collections(config());

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!drives(marker).contains("stop") && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }

        assertThat(drives(marker)).containsExactly("init", "stop");
    }

    // ---- getTableNames ---------------------------------------------------------------------------

    @Test
    void collectionsCollectsEveryBatchTheConnectorEmits(@TempDir Path dir) {
        // The function hands its names to a consumer it may call more than once - mongodb calls it per
        // batchSize names. A drive that keeps only the batch it saw last silently loses collections, and
        // a lost collection reads downstream as "that collection does not exist".
        PdkDataBrowser reader = reader(Synthetic.readFaceSource(dir), "synthetic.ReadFace");

        List<String> names = reader.collections(config());

        assertThat(names).containsExactly("orders", "shipments");
    }

    @Test
    void collectionsFailsWithACodeWhenTheConnectorDoesNotRegisterIt(@TempDir Path dir) {
        PdkDataBrowser reader = reader(Synthetic.emittingSource(dir), "synthetic.EmittingSource");

        assertThatThrownBy(() -> reader.collections(config()))
                .isInstanceOf(TapstateException.class)
                .extracting(e -> ((TapstateException) e).code().code())
                .isEqualTo("connector.capability-missing");
    }

    // ---- getTableInfo ----------------------------------------------------------------------------

    @Test
    void statsCarriesTheRowCountAndSizesTheConnectorReports(@TempDir Path dir) {
        PdkDataBrowser reader = reader(Synthetic.readFaceSource(dir), "synthetic.ReadFace");

        DataBrowserTableInfo info = reader.stats(config(), "orders");

        assertThat(info.numOfRows()).isEqualTo(512L);
        assertThat(info.storageSize()).isEqualTo(4096L);
        assertThat(info.avgObjSize()).isEqualTo(8L);
    }

    @Test
    void statsFailsWithACodeWhenTheConnectorDoesNotRegisterIt(@TempDir Path dir) {
        PdkDataBrowser reader = reader(Synthetic.emittingSource(dir), "synthetic.EmittingSource");

        assertThatThrownBy(() -> reader.stats(config(), "orders"))
                .isInstanceOf(TapstateException.class)
                .extracting(e -> ((TapstateException) e).code().code())
                .isEqualTo("connector.capability-missing");
    }

    // ---- executeCommand --------------------------------------------------------------------------

    @Test
    void findPinsTheCommandToExecuteQuery(@TempDir Path dir) {
        // The command name is the connector's dispatch key: "execute" and "update" reach write paths on
        // the same function. It is assembled here and is not a caller input, so the read face has no
        // spelling that reaches anything but a query.
        PdkDataBrowser reader = reader(Synthetic.readFaceSource(dir), "synthetic.ReadFace");

        List<Map<String, Object>> rows = reader.find(config(), new DataBrowserQuery("orders", Map.of(), 10));

        assertThat(echoed(rows, "command")).isEqualTo("executeQuery");
    }

    @Test
    void findCollectsEveryResultBatchTheConnectorEmits(@TempDir Path dir) {
        // executeQuery hands its rows to a consumer per batch (mongodb's default batch is 1000), so a
        // drive that assumes one callback returns a truncated page - and a truncated page is read
        // downstream as "that is all there is", with nothing reporting otherwise.
        PdkDataBrowser reader = reader(Synthetic.readFaceSource(dir), "synthetic.ReadFace");

        List<Map<String, Object>> rows = reader.find(config(), new DataBrowserQuery("orders", Map.of(), 10));

        assertThat(rows).hasSize(4);
    }

    @Test
    void findCarriesTheRequestedSortIntoTheParams(@TempDir Path dir) {
        // The seam carries an order as a neutral field-and-direction pair; turning that into the encoding
        // one connector's query expects belongs here, in the bridge that already knows which connector it
        // is driving, and nowhere above it.
        PdkDataBrowser reader = reader(Synthetic.readFaceSource(dir), "synthetic.ReadFace");

        List<Map<String, Object>> rows = reader.find(config(),
                new DataBrowserQuery("orders", Map.of(), new DataBrowserSort("status", Direction.DESC), 10));

        assertThat(echoed(rows, "sort")).isEqualTo(Map.of("status", -1));
    }

    @Test
    void findOmitsTheSortParamWhenTheRequestAsksForNoOrder(@TempDir Path dir) {
        // No order means the database's own, and that is a real answer rather than a missing one. Sending
        // an empty or null sort instead would be a request for an order nobody asked for, which is the one
        // thing this face promised not to impose.
        PdkDataBrowser reader = reader(Synthetic.readFaceSource(dir), "synthetic.ReadFace");

        List<Map<String, Object>> rows = reader.find(config(), new DataBrowserQuery("orders", Map.of(), 10));

        assertThat(echoed(rows, "sort")).isEqualTo("<none-was-sent>");
    }

    @Test
    void findNamesTheConnectionsOwnDatabaseInTheParams(@TempDir Path dir) {
        // Which database a read may touch follows from the connection, never from the request. Leaving
        // the param out happens to work against one connector, which fills its own in when the request
        // omits it - the other mongo variants do not, and a read that lands in the wrong database, or in
        // none, reports nothing wrong. Three databases share one mongod here, two of them ours.
        PdkDataBrowser reader = reader(Synthetic.readFaceSource(dir), "synthetic.ReadFace");

        List<Map<String, Object>> rows = reader.find(config("shop"), new DataBrowserQuery("orders", Map.of(), 10));

        assertThat(echoed(rows, "database-as-it-arrived")).isEqualTo("shop");
    }

    @Test
    void findOmitsTheDatabaseParamWhenTheConnectionCarriesNone(@TempDir Path dir) {
        // Nothing validates that a stored connection's settings name a database, so this is reachable.
        // Sending the key with a null value is the one answer that is worse than either alternative: it
        // names no database and, being present, stops the connector filling its own in. Omit it instead,
        // which leaves that connection exactly where it was before this face existed.
        PdkDataBrowser reader = reader(Synthetic.readFaceSource(dir), "synthetic.ReadFace");

        List<Map<String, Object>> rows = reader.find(config(), new DataBrowserQuery("orders", Map.of(), 10));

        assertThat(echoed(rows, "database-as-it-arrived")).isEqualTo("<none-was-sent>");
    }

    @Test
    void findCarriesTheCollectionIntoTheParams(@TempDir Path dir) {
        PdkDataBrowser reader = reader(Synthetic.readFaceSource(dir), "synthetic.ReadFace");

        List<Map<String, Object>> rows = reader.find(config(), new DataBrowserQuery("orders", Map.of(), 10));

        assertThat(echoed(rows, "collection")).isEqualTo("orders");
    }

    @Test
    void findHandsTheConnectorAParamsMapItCanWriteInto(@TempDir Path dir) {
        // A connector fills a missing param into the caller's own map rather than a copy - mongodb puts
        // the connection's database in when the request omits it. An immutable map throws there, and
        // only on the paths that omit that param, so it stays green until it does not.
        PdkDataBrowser reader = reader(Synthetic.readFaceSource(dir), "synthetic.ReadFace");

        assertThatCode(() -> reader.find(config(), new DataBrowserQuery("orders", Map.of(), 10)))
                .doesNotThrowAnyException();
    }

    @Test
    void findFailsWithACodeWhenTheConnectorReportsAnError(@TempDir Path dir) {
        // The failure arrives through the result rather than as a throw, so a drive that reads only
        // getResult() returns an empty page for a query that in fact failed.
        PdkDataBrowser reader = reader(Synthetic.erroringQuerySource(dir), "synthetic.ErroringQuery");

        assertThatThrownBy(() -> reader.find(config(), new DataBrowserQuery("orders", Map.of(), 10)))
                .isInstanceOf(TapstateException.class)
                .extracting(e -> ((TapstateException) e).code().code())
                .isEqualTo("connector.read-failed");
    }

    @Test
    void findFailsWithACodeWhenTheConnectorThrows(@TempDir Path dir) {
        PdkDataBrowser reader = reader(Synthetic.throwingQuerySource(dir), "synthetic.ThrowingQuery");

        assertThatThrownBy(() -> reader.find(config(), new DataBrowserQuery("orders", Map.of(), 10)))
                .isInstanceOf(TapstateException.class)
                .extracting(e -> ((TapstateException) e).code().code())
                .isEqualTo("connector.read-failed");
    }

    @Test
    void findFailsWithACodeWhenTheConnectorDoesNotRegisterIt(@TempDir Path dir) {
        // The read face is reachable by a user, so a connector that cannot serve it is a coded refusal
        // naming the connector and the capability - not the bare crash a caller invariant would take.
        PdkDataBrowser reader = reader(Synthetic.emittingSource(dir), "synthetic.EmittingSource");

        assertThatThrownBy(() -> reader.find(config(), new DataBrowserQuery("orders", Map.of(), 10)))
                .isInstanceOf(TapstateException.class)
                .extracting(e -> ((TapstateException) e).code().code())
                .isEqualTo("connector.capability-missing");
    }

    /** The value the read-face connector echoed back for {@code what}, or null if it echoed no such row. */
    private static Object echoed(List<Map<String, Object>> rows, String what) {
        return rows.stream()
                .filter(row -> what.equals(row.get("echoed")))
                .map(row -> row.get("value"))
                .findFirst()
                .orElse(null);
    }
}
