package io.tapstate.adapters.pdk;

import io.tapstate.core.common.TapstateException;
import io.tapstate.spi.store.ConnectionConfig;
import io.tapdata.pdk.apis.entity.ExecuteResult;
import io.tapdata.pdk.apis.entity.TapExecuteCommand;
import io.tapdata.pdk.apis.functions.connection.GetTableInfoFunction;
import io.tapdata.pdk.apis.functions.connection.GetTableNamesFunction;
import io.tapdata.pdk.apis.functions.connection.TableInfo;
import io.tapdata.pdk.apis.functions.connector.source.ExecuteCommandFunction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The PDK bridge for reading a store: it provisions a connector, refuses it with a code if it will
 * not load or its declared API level is incompatible, and drives the three read functions a connector
 * may register — listing its collections, reporting one collection's size, and running a query. The
 * PDK types stay inside this class; neither the requests nor the results carry any of them.
 *
 * <p>Unlike the capture read, this face is reachable by a user asking to look at their data, so a
 * connector that does not register the function a verb needs is a coded refusal naming the connector
 * and the capability — not the bare crash a caller-invariant violation would take. A connector that
 * fails while reading is likewise coded.
 *
 * <p>Three properties of the frozen contract shape the drive, and each one fails silently if
 * ignored:
 *
 * <ul>
 *   <li>Both the name listing and the query hand their results to a consumer they may call <em>more
 *       than once</em> — a batch at a time. Keeping only the last call returns a truncated answer
 *       that nothing downstream can tell apart from a complete one.
 *   <li>A query reports its failure through the result it hands back, not only by throwing. Reading
 *       just the rows off that result turns a failed query into an empty page.
 *   <li>A connector fills a param it needs but was not given into the caller's own map, so the map
 *       handed to it is mutable. An immutable one throws — and only on the requests that omit that
 *       param, which is why it stays green until it does not.
 * </ul>
 */
public final class PdkStoreReader {

    /**
     * The one command the read face dispatches. A connector routes writes through this same function
     * under other command names, so pinning it here is what makes the face read-only: it is assembled,
     * never accepted from a caller, so no request spelling reaches anything but a query.
     */
    private static final String QUERY_COMMAND = "executeQuery";

    /** How many collection names to ask for per consumer call; the listing is collected whole regardless. */
    private static final int NAME_BATCH_SIZE = 1000;

    private final ConnectorProvisioner provisioner;

    public PdkStoreReader(ConnectorProvisioner provisioner) {
        this.provisioner = provisioner;
    }

    /** Lists the collections the connection's own database holds, in the order the connector reports them. */
    public List<String> tableNames(ConnectionConfig config) {
        PdkConnector connector = open(config);
        try {
            GetTableNamesFunction names = require(
                    connector, connector.functions().getGetTableNamesFunction(), "getTableNames");
            List<String> collected = new ArrayList<>();
            drive(connector, () -> {
                connector.connector().init(connector.context());
                // The consumer is called once per batch, so every call accumulates - assigning here would
                // return only the connector's last batch and lose every collection before it.
                names.tableNames(connector.context(), NAME_BATCH_SIZE, collected::addAll);
                return null;
            });
            return collected;
        } finally {
            connector.stopQuietly();
            connector.close();
        }
    }

    /** Reports what the connector knows about one collection's size. */
    public StoreTableInfo tableInfo(ConnectionConfig config, String collection) {
        PdkConnector connector = open(config);
        try {
            GetTableInfoFunction info = require(
                    connector, connector.functions().getGetTableInfoFunction(), "getTableInfo");
            TableInfo reported = drive(connector, () -> {
                connector.connector().init(connector.context());
                return info.getTableInfo(connector.context(), collection);
            });
            return reported == null
                    ? new StoreTableInfo(null, null, null)
                    : new StoreTableInfo(reported.getNumOfRows(), reported.getStorageSize(), reported.getAvgObjSize());
        } finally {
            connector.stopQuietly();
            connector.close();
        }
    }

    /** Runs {@code query} against the connection's own database and returns the rows it matched. */
    public List<Map<String, Object>> query(ConnectionConfig config, StoreQuery query) {
        PdkConnector connector = open(config);
        try {
            ExecuteCommandFunction execute = require(
                    connector, connector.functions().getExecuteCommandFunction(), "executeCommand");
            List<Map<String, Object>> rows = new ArrayList<>();
            // A failure arrives through the result rather than as a throw, so it is captured as the drive
            // runs and raised after it - returning the rows collected so far would report a failed query
            // as a short page, which reads exactly like a complete one.
            AtomicReference<Throwable> reported = new AtomicReference<>();
            drive(connector, () -> {
                connector.connector().init(connector.context());
                TapExecuteCommand command = TapExecuteCommand.create()
                        .command(QUERY_COMMAND)
                        .params(params(query));
                execute.execute(connector.context(), command, result -> collect(result, rows, reported));
                return null;
            });
            Throwable failure = reported.get();
            if (failure != null) {
                throw readFailed(connector.connectorId(), failure);
            }
            return rows;
        } finally {
            connector.stopQuietly();
            connector.close();
        }
    }

    // ---- drive helpers ---------------------------------------------------------------------------

    private PdkConnector open(ConnectionConfig config) {
        return PdkConnector.open(
                config.connectorId(), provisioner.resolve(config.connectorId()), config.settings());
    }

    /**
     * The params one query is driven with. Mutable by contract, not by accident: a connector fills a
     * param it needs but was not given into this very map, so an immutable one throws inside the
     * connector — on those requests only.
     *
     * <p>The map carries what the read needs and nothing that would widen it: no command to dispatch
     * on, and no database, so the read reaches only what the connection already points at.
     */
    private static Map<String, Object> params(StoreQuery query) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("collection", query.collection());
        // A filter is always present, empty meaning every row: a connector hands its absence straight to
        // the driver as a null filter, which the driver rejects.
        params.put("filter", new LinkedHashMap<>(query.filter()));
        // An int, not a long: the connector casts this straight to int, so a long fails at the cast.
        params.put("limit", query.limit());
        return params;
    }

    /** Accumulates one result batch, remembering the first failure a batch reports instead of its rows. */
    private static void collect(ExecuteResult<?> result, List<Map<String, Object>> rows,
                                AtomicReference<Throwable> reported) {
        if (result == null) {
            return;
        }
        if (result.getError() != null) {
            reported.compareAndSet(null, result.getError());
            return;
        }
        if (!(result.getResult() instanceof List<?> batch)) {
            return;
        }
        for (Object row : batch) {
            if (row instanceof Map<?, ?> fields) {
                Map<String, Object> copy = new LinkedHashMap<>();
                fields.forEach((name, value) -> copy.put(String.valueOf(name), value));
                rows.add(copy);
            }
        }
    }

    /** Runs a read action under the connector loader, mapping a connector-side failure to a code. */
    private static <T> T drive(PdkConnector connector, PdkConnector.Action<T> action) {
        try {
            return connector.underLoader(action);
        } catch (TapstateException e) {
            throw e;
        } catch (Throwable t) {
            throw readFailed(connector.connectorId(), t);
        }
    }

    private static TapstateException readFailed(String connectorId, Throwable cause) {
        return new TapstateException(ConnectorError.READ_FAILED,
                Map.of("connector", connectorId, "detail", PdkSchemaDiscoverer.detail(cause)), cause);
    }

    /** The registered function, or a coded refusal naming the connector and the capability it lacks. */
    private static <T> T require(PdkConnector connector, T function, String capability) {
        if (function == null) {
            throw new TapstateException(ConnectorError.CAPABILITY_MISSING,
                    Map.of("connector", connector.connectorId(), "capability", capability), null);
        }
        return function;
    }
}
