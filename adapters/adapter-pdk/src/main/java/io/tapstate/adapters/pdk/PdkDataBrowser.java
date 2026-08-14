package io.tapstate.adapters.pdk;

import io.tapstate.core.common.TapstateException;
import io.tapstate.spi.store.ConnectionConfig;
import io.tapstate.spi.store.DataBrowser;
import io.tapstate.spi.store.DataBrowserFilter;
import io.tapstate.spi.store.DataBrowserFilter.All;
import io.tapstate.spi.store.DataBrowserFilter.Any;
import io.tapstate.spi.store.DataBrowserFilter.Match;
import io.tapstate.spi.store.DataBrowserFilter.Operator;
import io.tapstate.spi.store.DataBrowserPreview;
import io.tapstate.spi.store.DataBrowserQuery;
import io.tapstate.spi.store.DataBrowserSort;
import io.tapstate.spi.store.DataBrowserTableInfo;
import io.tapdata.pdk.apis.entity.ExecuteResult;
import io.tapdata.pdk.apis.entity.TapExecuteCommand;
import io.tapdata.pdk.apis.functions.connection.GetTableInfoFunction;
import io.tapdata.pdk.apis.functions.connection.GetTableNamesFunction;
import io.tapdata.pdk.apis.functions.connection.TableInfo;
import io.tapdata.pdk.apis.functions.connector.source.ExecuteCommandFunction;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * The PDK bridge for reading a store: it takes a live connector instance from the pool, refuses the
 * read with a code if the connector will not load or does not register the function the verb needs,
 * and drives the three read functions — listing its collections, reporting one collection's size, and
 * running a query. The PDK types stay inside this class; neither the requests nor the results carry
 * any of them.
 *
 * <p>Instances are pooled rather than opened per read, and the reason is not only cost: opening a
 * connector builds a fresh isolated class loader and — once initialized — the connector's own driver
 * connection pool, so a read face that opened one per call could not carry a caller that reads on a
 * timer. Initialization therefore happens once, when the instance enters the pool, not on every read.
 * The pool is live state, so this reader owns a lifecycle: {@link #close()} hands it all back.
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
public final class PdkDataBrowser implements DataBrowser {

    /**
     * The one command the read face dispatches. A connector routes writes through this same function
     * under other command names, so pinning it here is what makes the face read-only: it is assembled,
     * never accepted from a caller, so no request spelling reaches anything but a query.
     */
    private static final String QUERY_COMMAND = "executeQuery";

    /** How many collection names to ask for per consumer call; the listing is collected whole regardless. */
    private static final int NAME_BATCH_SIZE = 1000;

    private final ConnectorInstancePool<PdkConnector> pool;
    private final ScheduledExecutorService evictions;

    public PdkDataBrowser(ConnectorProvisioner provisioner) {
        this(provisioner, ConnectorInstancePool.DEFAULTS, Clock.systemUTC());
    }

    PdkDataBrowser(ConnectorProvisioner provisioner, ConnectorInstancePool.Limits limits, Clock clock) {
        this.pool = new ConnectorInstancePool<>(
                config -> openAndInitialize(provisioner, config), PdkDataBrowser::shutDown, limits, clock);
        this.evictions = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "data-browser-eviction");
            thread.setDaemon(true);
            return thread;
        });
        // The pool keeps no timer of its own, so an idle instance is only given up if something asks it
        // to be. Checking on the next read instead would never fire on the face nobody is using, which
        // is exactly the face whose connections should have been handed back.
        long period = Math.max(1, limits.idle().toMillis() / 2);
        evictions.scheduleWithFixedDelay(pool::sweep, period, period, TimeUnit.MILLISECONDS);
    }

    @Override
    public List<String> collections(ConnectionConfig config) {
        return pool.call(config, connector -> {
            GetTableNamesFunction names = require(
                    connector, connector.functions().getGetTableNamesFunction(), "getTableNames");
            List<String> collected = new ArrayList<>();
            // The consumer is called once per batch, so every call accumulates - assigning here would
            // return only the connector's last batch and lose every collection before it.
            drive(connector, () -> {
                names.tableNames(connector.context(), NAME_BATCH_SIZE, collected::addAll);
                return null;
            });
            return collected;
        });
    }

    @Override
    public DataBrowserTableInfo stats(ConnectionConfig config, String collection) {
        return pool.call(config, connector -> {
            GetTableInfoFunction info = require(
                    connector, connector.functions().getGetTableInfoFunction(), "getTableInfo");
            TableInfo reported = drive(connector, () -> info.getTableInfo(connector.context(), collection));
            return reported == null
                    ? new DataBrowserTableInfo(null, null, null)
                    : new DataBrowserTableInfo(reported.getNumOfRows(), reported.getStorageSize(), reported.getAvgObjSize());
        });
    }

    @Override
    public DataBrowserPreview find(ConnectionConfig config, DataBrowserQuery query) {
        return pool.call(config, connector -> {
            ExecuteCommandFunction execute = require(
                    connector, connector.functions().getExecuteCommandFunction(), "executeCommand");
            List<Map<String, Object>> rows = new ArrayList<>();
            // A failure arrives through the result rather than as a throw, so it is captured as the drive
            // runs and raised after it - returning the rows collected so far would report a failed query
            // as a short page, which reads exactly like a complete one.
            AtomicReference<Throwable> reported = new AtomicReference<>();
            drive(connector, () -> {
                TapExecuteCommand command = TapExecuteCommand.create()
                        .command(QUERY_COMMAND)
                        .params(params(config, query, beyond(query.limit())));
                execute.execute(connector.context(), command, result -> collect(result, rows, reported));
                return null;
            });
            Throwable failure = reported.get();
            if (failure != null) {
                throw readFailed(connector.connectorId(), failure);
            }
            // A read that was stopped mid-flight returns here looking like one that finished: the loop
            // that gave up neither threw nor reported, and the rows that did arrive are a short answer
            // no other signal contradicts. Asking afterwards is the only thing that separates the two,
            // and this is the one verb it applies to - the name listing never asks about its own
            // liveness, so it cannot end early without saying so.
            if (!connector.isAlive()) {
                throw new TapstateException(ConnectorError.READ_ABANDONED,
                        Map.of("connector", connector.connectorId()), null);
            }
            // The row past the bound arrived, so the collection holds more than this read carries. The
            // row itself is not part of the answer: a caller that asked for ten and is handed eleven has
            // had its bound broken to satisfy a footnote.
            boolean moreAvailable = rows.size() > query.limit();
            if (moreAvailable) {
                rows.subList(query.limit(), rows.size()).clear();
            }
            return new DataBrowserPreview(rows, approximateTotal(connector, query), moreAvailable);
        });
    }

    /** Hands back every pooled connector and stops evicting. */
    @Override
    public void close() {
        evictions.shutdownNow();
        pool.close();
    }

    /**
     * One row past what the caller asked for: whether it arrives is the only honest way to tell a
     * collection that ends at the bound from one that merely reaches it. A full answer looks like the
     * obvious signal and is wrong exactly when the collection holds the bound and not one more.
     *
     * <p>An unbounded request has no row past it to ask for, so it is left as it is; nothing is being
     * held back from a caller that asked for everything.
     */
    private static int beyond(int limit) {
        return limit == Integer.MAX_VALUE ? limit : limit + 1;
    }

    /**
     * How many rows the collection holds, or null when that could not be told cheaply. Offered only
     * for an unfiltered read, and only off the store's own metadata: a connector counts a filtered
     * collection by scanning it, which on a large one spends the whole read budget answering a
     * footnote — and is the one query that cannot be narrowed to finish sooner.
     *
     * <p>A count nobody can supply is a missing footnote, never a failed read. A connector that
     * registers no size function, one that reports nothing, and one that fails reporting all land on
     * null, which this face already reads as "not reported" rather than as zero — refusing instead
     * would deny a working read over a connector that offers one function fewer.
     */
    private static Long approximateTotal(PdkConnector connector, DataBrowserQuery query) {
        if (query.filter() != null) {
            return null;
        }
        GetTableInfoFunction info = connector.functions().getGetTableInfoFunction();
        if (info == null) {
            return null;
        }
        try {
            TableInfo reported = drive(connector, () -> info.getTableInfo(connector.context(), query.collection()));
            return reported == null ? null : reported.getNumOfRows();
        } catch (RuntimeException failedToCount) {
            return null;
        }
    }

    // ---- the pooled instance ---------------------------------------------------------------------

    /**
     * Opens one connector for {@code config} and initializes it, which is the step that builds whatever
     * the connector holds at runtime — for a database connector, its own connection pool. Pooling an
     * un-initialized instance would rebuild that on every read and save nothing.
     */
    private static PdkConnector openAndInitialize(ConnectorProvisioner provisioner, ConnectionConfig config) {
        PdkConnector connector = PdkConnector.open(
                config.connectorId(), provisioner.resolve(config.connectorId()), config.settings());
        try {
            drive(connector, () -> {
                connector.connector().init(connector.context());
                return null;
            });
        } catch (RuntimeException | Error e) {
            shutDown(connector);
            throw e;
        }
        return connector;
    }

    /** Stops and closes one pooled connector; the pool calls this exactly once per instance. */
    private static void shutDown(PdkConnector connector) {
        connector.stopQuietly();
        connector.close();
    }

    // ---- drive helpers ---------------------------------------------------------------------------

    /**
     * The params one query is driven with. Mutable by contract, not by accident: a connector fills a
     * param it needs but was not given into this very map, so an immutable one throws inside the
     * connector — on those requests only.
     *
     * <p>The map carries what the read needs and nothing that would widen it: no command to dispatch
     * on, and no database, so the read reaches only what the connection already points at.
     */
    private static Map<String, Object> params(ConnectionConfig config, DataBrowserQuery query, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        // Which database a read may touch follows from the connection and is assembled here, never taken
        // from the request. Omitting it leans on a fallback only one connector has, and a read that lands
        // in the wrong database reports nothing wrong.
        //
        // Only when the connection names one. Nothing validates that it does, and sending the key with a
        // null value is worse than sending nothing: it names no database and, being present, stops the
        // connector filling its own in.
        Object database = config.settings().get("database");
        if (database != null) {
            params.put("database", database);
        }
        params.put("collection", query.collection());
        // A filter is always present, empty meaning every row: a connector hands its absence straight to
        // the driver as a null filter, which the driver rejects.
        params.put("filter", translate(query.filter()));
        // Only when an order was asked for. Absent means the database's own order, and the key has to be
        // left out to say that: a connector reads a present sort and an absent one differently, so an
        // empty map here would be a request for an order rather than the absence of one.
        DataBrowserSort sort = query.sort();
        if (sort != null) {
            Map<String, Object> ordering = new LinkedHashMap<>();
            ordering.put(sort.field(), sort.direction() == DataBrowserSort.Direction.ASC ? 1 : -1);
            params.put("sort", ordering);
        }
        // An int, not a long: the connector casts this straight to int, so a long fails at the cast.
        // What is sent is the drive's own bound, one past what the caller asked for, so the request the
        // caller made stays what it was and the extra row never leaves this class.
        params.put("limit", limit);
        return params;
    }

    /**
     * One filter in the query language the driven connector speaks. This is the only place that knows
     * that language: the seam above carries Tapstate's own vocabulary, so nothing a caller sends is ever
     * a fragment of a backend query, and a second backend shape is a second translation here rather than
     * a change to any surface.
     *
     * <p>A null filter becomes the empty document, which every row matches. The absence has to be spelt
     * out rather than left out, because a connector hands an absent param straight to its driver as a
     * null filter and the driver refuses that.
     */
    private static Map<String, Object> translate(DataBrowserFilter filter) {
        return switch (filter) {
            case null -> new LinkedHashMap<>();
            case Match match -> term(match);
            case All all -> combination("$and", all.terms());
            case Any any -> combination("$or", any.terms());
        };
    }

    private static Map<String, Object> combination(String connective, List<Match> terms) {
        List<Map<String, Object>> translated = new ArrayList<>(terms.size());
        terms.forEach(term -> translated.add(term(term)));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put(connective, translated);
        return document;
    }

    /**
     * One term as an operator-keyed test on its field. Keyed even for equality, where the bare
     * {@code {field: value}} form would read the same: that form takes on a second meaning when the value
     * is itself a document, and the value here comes from a caller.
     */
    private static Map<String, Object> term(Match match) {
        Map<String, Object> test = new LinkedHashMap<>();
        test.put(operator(match.operator()), value(match));
        Map<String, Object> document = new LinkedHashMap<>();
        document.put(match.field(), test);
        return document;
    }

    private static String operator(Operator operator) {
        return switch (operator) {
            case EQ -> "$eq";
            case NE -> "$ne";
            case GT -> "$gt";
            case GTE -> "$gte";
            case LT -> "$lt";
            case LTE -> "$lte";
            case IN -> "$in";
            case EXISTS -> "$exists";
            // The vocabulary has no pattern in it, so this is the one operator whose value is not sent as
            // it arrived: it is a substring to find, and it reaches the backend as a pattern.
            case CONTAINS -> "$regex";
        };
    }

    private static Object value(Match match) {
        if (match.operator() != Operator.CONTAINS) {
            return match.value();
        }
        // Quoted whole, so every character of the value is a character to find rather than a pattern to
        // run. Spliced in raw, the one operator that takes free text would be a way to express a pattern
        // through a vocabulary that deliberately has none — including patterns that never finish.
        return Pattern.quote((String) match.value());
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
