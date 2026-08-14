package io.tapstate.e2e;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The {@link Endpoints} driver for a Mongo store, addressed by the connection string the resource
 * carries as its {@code uri}. Rule R3 locks this driver to adapter-mongo-store for the rings; this
 * module is deliberately not one of them.
 *
 * <p>Identity is spelled {@code id} here, as a plain field, exactly as every specification spells it.
 * The product's own sink writes it that way - a document it lands carries {@code id} as a field and
 * leaves {@code _id} to the store - and the driver seeds and reads the same shape, so one where
 * clause means one thing whether the document was seeded by the harness or landed by the product.
 * The store's internal {@code _id} never crosses this seam in either direction.
 */
final class MongoEndpoints implements Endpoints {

    private static final String INTERNAL_KEY = "_id";
    private static final String IDENTITY_FIELD = SeedRows.ID;
    private static final String SEQUENCE_FIELD = "seq";
    private static final String TOUCHED_FIELD = "touched";

    /** The setting this store is addressed by: one connection string naming host, port and database. */
    private static final String CONNECTION_STRING = "uri";

    private final Map<String, MongoClient> clientsByUri = new LinkedHashMap<>();

    @Override
    public void seed(EndpointAddress address, String table, List<Map<String, Object>> rows) {
        MongoCollection<Document> collection = collection(address, table);
        collection.drop();
        List<Document> documents = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            Document document = new Document();
            row.forEach((column, value) -> document.append(column, normalized(value)));
            documents.add(document);
        }
        // Seeding nothing is how a specification says the collection holds nothing, and the driver
        // rejects a write of no documents outright; the drop above has already made it so.
        if (!documents.isEmpty()) {
            collection.insertMany(documents);
        }
    }

    /**
     * Integers widened before writing: a specification's whole numbers arrive as whatever fit the
     * YAML parser, and a store holding 1 as Int32 would then disagree with a where asking for 1L.
     */
    private static Object normalized(Object value) {
        return value instanceof Integer number ? number.longValue() : value;
    }

    /**
     * The one matching document, without the store's internal key: {@code _id} is the store's own
     * bookkeeping, not part of any row a specification wrote or the product landed, and a document
     * read that surfaced it would teach authors a field that means nothing anywhere else.
     */
    @Override
    public Optional<Map<String, Object>> fetch(EndpointAddress address, String table, Map<String, Object> where) {
        Document filter = new Document();
        where.forEach((setting, value) -> filter.append(setting, normalized(value)));
        List<Document> matches = collection(address, table).find(filter).limit(2).into(new ArrayList<>());
        if (matches.isEmpty()) {
            return Optional.empty();
        }
        if (matches.size() > 1) {
            throw new EnvelopeException(
                    "more than one document in " + table + " matches " + where
                            + "; a document read must locate exactly one");
        }
        Map<String, Object> document = new LinkedHashMap<>();
        matches.getFirst().forEach((field, value) -> {
            if (!INTERNAL_KEY.equals(field)) {
                document.put(field, value);
            }
        });
        return Optional.of(document);
    }

    @Override
    public void cdc(EndpointAddress address, String table, CdcOp op, long rows) {
        MongoCollection<Document> collection = collection(address, table);
        switch (op) {
            case INSERT -> insertRange(collection, highestId(collection) + 1, rows);
            case UPDATE -> collection.updateMany(
                    Filters.in(IDENTITY_FIELD, lowestIds(collection, rows)),
                    Updates.set(TOUCHED_FIELD, true));
            case DELETE -> collection.deleteMany(Filters.in(IDENTITY_FIELD, lowestIds(collection, rows)));
        }
    }

    /**
     * Applies each named change in order, as the write that change is.
     *
     * <p>An update or a delete that matches nothing fails rather than passing quietly: the row a case
     * names is the row its assertion is about, so reaching none means the specification and what the
     * collection holds have drifted apart, and a silent no-op would let the run assert the absence of a
     * change it never made.
     */
    @Override
    public void cdc(EndpointAddress address, String table, List<CdcChange> changes) {
        MongoCollection<Document> collection = collection(address, table);
        for (CdcChange change : changes) {
            switch (change) {
                case CdcChange.Insert insert -> collection.insertOne(normalizedDocument(insert.row()));
                case CdcChange.Update update -> reached(
                        collection.updateMany(filter(update.where()), Updates.combine(sets(update.set())))
                                .getMatchedCount(),
                        table, "update");
                case CdcChange.Delete delete -> reached(
                        collection.deleteMany(filter(delete.where())).getDeletedCount(), table, "delete");
            }
        }
    }

    /**
     * Values widened on the way in, the same as a seed's. A where asking for 1 has to match a document
     * seeded with 1, and the two would be Int32 and Int64 if only one side widened.
     */
    private static Document normalizedDocument(Map<String, Object> row) {
        Document document = new Document();
        row.forEach((column, value) -> document.append(column, normalized(value)));
        return document;
    }

    private static Bson filter(Map<String, Object> where) {
        return Filters.and(where.entrySet().stream()
                .map(setting -> Filters.eq(setting.getKey(), normalized(setting.getValue())))
                .toList());
    }

    private static List<Bson> sets(Map<String, Object> values) {
        return values.entrySet().stream()
                .map(value -> Updates.set(value.getKey(), normalized(value.getValue())))
                .toList();
    }

    private static void reached(long documents, String table, String operation) {
        if (documents == 0) {
            throw new EnvelopeException(
                    "the " + operation + " on " + table + " reached no documents; the change names the "
                            + "document its assertion is about, so reaching none means the specification "
                            + "and what the collection holds have drifted apart");
        }
    }

    /**
     * Every document in the collection, read the way a user would - from outside the product, by a
     * driver that is not it. Whole documents rather than one field: what a value should be is the
     * caller's assertion to make, and a reader that already knows which field to look at cannot report
     * a document that landed in an unexpected shape.
     */
    public List<Document> documents(EndpointAddress address, String table) {
        return collection(address, table).find().into(new ArrayList<>());
    }

    /**
     * The lowest ids by order rather than by id &lt;= rows: after a delete the ids no longer start at
     * one, and a change meant to touch two documents would touch none. The SQL driver selects the same
     * way, so one specification means the same change against either store.
     */
    private static List<Object> lowestIds(MongoCollection<Document> collection, long rows) {
        List<Object> ids = new ArrayList<>();
        collection.find()
                .sort(new Document(IDENTITY_FIELD, 1))
                .limit((int) Math.min(rows, Integer.MAX_VALUE))
                .forEach(document -> ids.add(document.get(IDENTITY_FIELD)));
        return ids;
    }

    /**
     * Deliberately does nothing, and is written out rather than inherited so that it reads as a decision.
     *
     * <p>Re-emission answers a positional log: a row written before the reader's tail is positioned is
     * never delivered, and nothing observable says when positioning is done. A change stream is resumed
     * by a token the store itself issues, so the same window is not known to exist here - and rewriting
     * every document of a collection under test to chase a window that may not be there would cost more
     * than it could buy. A stalled await against a Mongo source therefore runs its bound out and fails
     * with its reading, which is the honest outcome for a stall nothing here can explain.
     */
    @Override
    public void redeliver(EndpointAddress address, String table) {
        // Intentionally empty; see above.
    }

    @Override
    public long count(EndpointAddress address, String table) {
        return collection(address, table).countDocuments();
    }

    /**
     * The collections the target holds. An empty expected collection and rows written to a differently
     * named one look identical from a count, and the second is the likelier mistake - so a witness that
     * finds nothing where it looked can say what is actually there.
     */
    List<String> collections(String uri) {
        ConnectionString connectionString = new ConnectionString(uri);
        String database = connectionString.getDatabase();
        if (database == null) {
            throw new EnvelopeException(
                    "the endpoint at " + uri + " names no database, so there are no collections to list");
        }
        return client(uri).getDatabase(database).listCollectionNames().into(new ArrayList<>());
    }

    @Override
    public void close() {
        clientsByUri.values().forEach(MongoClient::close);
        clientsByUri.clear();
    }

    private void insertRange(MongoCollection<Document> collection, long firstId, long rows) {
        List<Document> documents = new ArrayList<>();
        for (long id = firstId; id < firstId + rows; id++) {
            documents.add(new Document(IDENTITY_FIELD, id).append(SEQUENCE_FIELD, id));
        }
        if (!documents.isEmpty()) {
            collection.insertMany(documents);
        }
    }

    /**
     * The greatest id present, or zero for an empty collection. Identity is a plain field here, so
     * unlike the store's own key nothing guarantees a document carries it or carries it as a whole
     * number - a document the product landed may spell it any width, or not at all. A missing or
     * non-numeric id is named rather than let out as a cast or an unboxing of null, which would reach a
     * specification as a fault with no place in it.
     */
    private long highestId(MongoCollection<Document> collection) {
        Document highest = collection.find().sort(new Document(IDENTITY_FIELD, -1)).limit(1).first();
        if (highest == null) {
            return 0L;
        }
        Object id = highest.get(IDENTITY_FIELD);
        if (!(id instanceof Number number)) {
            throw new EnvelopeException(
                    "the highest document in " + collection.getNamespace().getCollectionName()
                            + " carries no numeric " + IDENTITY_FIELD + " (found " + id
                            + "), so an inserted row has nothing to continue from");
        }
        return number.longValue();
    }

    private MongoCollection<Document> collection(EndpointAddress address, String table) {
        String uri = address.text(CONNECTION_STRING);
        ConnectionString connectionString = new ConnectionString(uri);
        String database = connectionString.getDatabase();
        if (database == null) {
            throw new EnvelopeException(
                    "the endpoint at " + uri + " names no database, so there is no table to address");
        }
        return client(uri).getDatabase(database).getCollection(table);
    }

    private MongoClient client(String uri) {
        return clientsByUri.computeIfAbsent(uri, MongoClients::create);
    }
}
