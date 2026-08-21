package io.tapstate.core.dsl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import io.tapstate.core.common.TapstateType;
import io.tapstate.core.model.Resource;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * An upsert matches a write to an existing row by that row's key. A table that declares no key
 * cannot answer "which row is this", so a connector asked to upsert into it writes something - and
 * what it writes is not what the pipeline meant. Nothing downstream reports that, which is why the
 * refusal belongs here, before anything runs, rather than in whatever the target ends up holding.
 *
 * <p>These cases all judge the write mode against a discovered key, so every source they read has
 * been discovered. A source nobody discovered is a different situation and is judged as such: this
 * rule says nothing about a table it never saw.
 */
class WriteKeyRulesTest {

    private static final String SOURCE = """
            version: tapstate/v1
            kind: source
            id: src_orders
            connector: mysql
            config: { host: 10.10.0.5, username: u, password: p }
            mode: cdc
            tables: [ orders ]
            """;

    private static List<Resource> batch(String... documents) {
        DslParser parser = new DslParser();
        List<Resource> resources = new ArrayList<>();
        resources.add(parser.parse(SOURCE));
        for (String document : documents) {
            resources.add(parser.parse(document));
        }
        return resources;
    }

    /** A pipeline syncing to a target, written with the given {@code write_mode:} line. */
    private static String pipeline(String writeModeLine) {
        return """
                version: tapstate/v1
                kind: pipeline
                id: orders_out
                source: src_orders
                serve:
                  from: orders
                  sync: [ { id: out, source: src_orders%s } ]
                """.formatted(writeModeLine);
    }

    /** The source's one discovered table, holding the given key. */
    private static Map<String, List<DiscoveredTable>> model(List<String> primaryKey) {
        Map<String, TapstateType> columns = new LinkedHashMap<>();
        columns.put("id", TapstateType.INT64);
        columns.put("amount", TapstateType.DECIMAL);
        return Map.of("src_orders", List.of(new DiscoveredTable("orders", columns, primaryKey, null)));
    }

    /**
     * A keyless table the pipeline does not read cannot refuse the pipeline.
     *
     * <p>The discovered model holds every table the source resolves to, and a source selecting several
     * - or selecting none, which is how the whole database is read - puts them all in the map. Judging
     * all of them refuses an apply over a table this pipeline never reads and never writes, naming a
     * table no edit to the pipeline can do anything about. On the database this rule was written for,
     * that is 225 tables that have nothing to do with the one being synced.
     *
     * <p>The tables that reach the serve block are what the write is made of, so those are what is
     * judged - resolved the same way the row-expression rules resolve theirs.
     */
    @Test
    @DisplayName("a keyless table elsewhere in the source does not refuse a pipeline that reads another")
    void aKeylessTableThePipelineDoesNotReadIsNotJudged() {
        Map<String, TapstateType> columns = new LinkedHashMap<>();
        columns.put("id", TapstateType.INT64);
        Map<String, List<DiscoveredTable>> discovered = Map.of("src_orders", List.of(
                new DiscoveredTable("orders", columns, List.of("id"), null),
                new DiscoveredTable("events", columns, List.of(), null)));

        assertThatCode(() -> WriteKeyRules.validate(
                batch(pipeline(", write_mode: upsert")), discovered))
                .doesNotThrowAnyException();
    }

    /** The same model, read the other way round: the keyless table is the one being served. */
    @Test
    @DisplayName("the keyless table is still refused when it is the one the pipeline reads")
    void theKeylessTableIsStillRefusedWhenItIsTheOneRead() {
        Map<String, TapstateType> columns = new LinkedHashMap<>();
        columns.put("id", TapstateType.INT64);
        Map<String, List<DiscoveredTable>> discovered = Map.of("src_orders", List.of(
                new DiscoveredTable("orders", columns, List.of("id"), null),
                new DiscoveredTable("events", columns, List.of(), null)));
        String servesEvents = """
                version: tapstate/v1
                kind: pipeline
                id: events_out
                source: src_orders
                serve:
                  from: events
                  sync: [ { id: out, source: src_orders, write_mode: upsert } ]
                """;

        DslException thrown = catchThrowableOfType(DslException.class,
                () -> WriteKeyRules.validate(batch(servesEvents), discovered));

        assertThat(thrown).isNotNull();
        assertThat(thrown.args()).containsEntry("table", "events");
    }

    @Test
    @DisplayName("upsert into a table whose source declares no key is refused, naming the table")
    void upsertWithoutAKeyIsRefused() {
        DslException thrown = catchThrowableOfType(DslException.class,
                () -> WriteKeyRules.validate(batch(pipeline(", write_mode: upsert")), model(List.of())));

        assertThat(thrown).isNotNull();
        assertThat(thrown.code()).isEqualTo(DslError.UPSERT_NEEDS_KEY);
        assertThat(thrown.args()).containsEntry("table", "orders");
    }

    /**
     * The default is upsert, and it is written by leaving the field out - so a pipeline that never
     * mentions a write mode is exactly the one a stranger writes. A rule that only fired on the
     * spelled-out form would miss the common case entirely.
     */
    @Test
    @DisplayName("a sync with no write mode at all is judged as the upsert it defaults to")
    void anAbsentWriteModeIsJudgedAsUpsert() {
        DslException thrown = catchThrowableOfType(DslException.class,
                () -> WriteKeyRules.validate(batch(pipeline("")), model(List.of())));

        assertThat(thrown).isNotNull();
        assertThat(thrown.code()).isEqualTo(DslError.UPSERT_NEEDS_KEY);
    }

    /**
     * Append never matches a write to an existing row, so it has no use for a key. Refusing it would
     * turn a legitimate way of writing a keyless table into an error.
     */
    @Test
    @DisplayName("append into the same keyless table is allowed")
    void appendWithoutAKeyIsAllowed() {
        assertThatCode(() -> WriteKeyRules.validate(
                batch(pipeline(", write_mode: append")), model(List.of())))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("upsert into a table that declares a key is allowed")
    void upsertWithAKeyIsAllowed() {
        assertThatCode(() -> WriteKeyRules.validate(
                batch(pipeline(", write_mode: upsert")), model(List.of("id"))))
                .doesNotThrowAnyException();
    }

    /**
     * Absent is not empty. A source nobody discovered contributes no tables, and this rule has
     * nothing to say about tables it never saw - reporting "this table has no key" about one of them
     * would be inventing a fact. The discovery obligation is a separate rule's concern.
     */
    @Test
    @DisplayName("a source that was never discovered is not reported as a table without a key")
    void anUndiscoveredSourceIsNotReported() {
        assertThatCode(() -> WriteKeyRules.validate(
                batch(pipeline(", write_mode: upsert")), Map.of()))
                .doesNotThrowAnyException();
    }
}
