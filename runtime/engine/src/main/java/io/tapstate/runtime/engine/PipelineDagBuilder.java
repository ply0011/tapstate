package io.tapstate.runtime.engine;

import com.hazelcast.function.FunctionEx;
import com.hazelcast.function.SupplierEx;
import com.hazelcast.jet.core.DAG;
import com.hazelcast.jet.core.Edge;
import com.hazelcast.jet.core.ProcessorMetaSupplier;
import com.hazelcast.jet.core.ProcessorSupplier;
import com.hazelcast.jet.core.Vertex;
import com.hazelcast.jet.core.processor.Processors;
import io.tapstate.core.model.FromClause;
import io.tapstate.core.model.FromRef;
import io.tapstate.core.model.PipelineResource;
import io.tapstate.core.model.ServeBlock;
import io.tapstate.core.model.Step;
import io.tapstate.core.model.SyncElement;
import io.tapstate.core.model.TransformBody;
import io.tapstate.core.model.ViewBlock;
import io.tapstate.spi.sink.SinkWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Compiles one pipeline into one Jet DAG: source vertices to a linear transform chain to serve
 * sink vertices, wired by explicit edges. The builder is topology only - it submits nothing and
 * re-judges nothing - so a caller can assert the graph shape without running a job.
 */
public final class PipelineDagBuilder {

    /**
     * The name prefix every serve-sink vertex carries. The engine picks serve sinks out of a job's
     * metrics by this prefix to sum the records that reached them, so the prefix is a shared contract
     * between the builder that stamps it and the engine that reads it.
     */
    static final String SERVE_VERTEX_PREFIX = "serve.";

    /**
     * The name prefix every view materialization vertex carries. Kept distinct from the serve prefix so
     * a measurement can still say which output it belongs to - delivered outward, or materialized into
     * the state store - while both count as output the engine sums.
     */
    static final String VIEW_VERTEX_PREFIX = "view.";

    private PipelineDagBuilder() {
    }

    /** Builds the Jet DAG for a validated pipeline against the given leaf and reference bindings. */
    public static DAG build(PipelineResource pipeline, DagBindings bindings) {
        return build(pipeline, bindings, null);
    }

    /**
     * Builds the Jet DAG for a validated pipeline. When {@code sinkAck} is present each serve sink
     * advances a durable sink-acked watermark through it; when it is null the sinks are the no-ack
     * variant, so an ack-less run still builds. The topology is identical either way — the ack only
     * changes which sink vertex the serve block wires.
     */
    public static DAG build(PipelineResource pipeline, DagBindings bindings, SinkAckBinding sinkAck) {
        DAG dag = new DAG();
        Map<String, Vertex> byKey = new HashMap<>();
        // Jet rejects two edges that share a source or destination ordinal, so every edge takes the
        // next free ordinal on each of its endpoints; a fan-in (union) and a fan-out (multi-sink)
        // then wire without collision.
        Map<Vertex, Integer> outboundOrdinal = new HashMap<>();
        Map<Vertex, Integer> inboundOrdinal = new HashMap<>();

        for (String sourceId : pipeline.sources()) {
            List<String> sourceKeys = bindings.sourceKeys().apply(sourceId);
            if (sourceKeys == null || sourceKeys.isEmpty()) {
                throw new IllegalStateException("source '" + sourceId + "' has no source vertex keys");
            }
            for (String sourceKey : sourceKeys) {
                byKey.put(sourceKey, dag.newVertex(sourceKey, bindings.sourceVertices().apply(sourceKey)));
            }
        }

        if (pipeline.transforms() != null) {
            for (Step step : pipeline.transforms()) {
                Vertex vertex = transformVertex(dag, step, bindings);
                byKey.put(step.id(), vertex);
                connect(dag, resolveClause(step.from(), byKey, bindings), vertex,
                        outboundOrdinal, inboundOrdinal);
            }
        }

        if (pipeline.view() instanceof ViewBlock.Use) {
            throw new IllegalArgumentException(
                    "view block is a use-reference; resolve it to an inline view first");
        }
        // A view id names data, not a vertex that can be read from: the view compiles to a terminal
        // sink, so a downstream reference to it resolves to whatever the view itself reads. Without
        // this the parser's own default - serve.from = the view's id - resolves to no vertex at all.
        Map<String, List<Vertex>> readsAs = new HashMap<>();

        if (pipeline.view() instanceof ViewBlock.Inline view) {
            // A declared view IS its own instruction to materialize: the pipeline needs no serve block
            // to reach the state store, and the vertex is a terminal sink like any other.
            List<Vertex> upstream = resolve(view.from(), byKey, bindings);
            Vertex vertex = dag.newVertex(VIEW_VERTEX_PREFIX + view.id(),
                    sinkVertex(bindings.viewSinks().apply(view), sinkAck));
            connect(dag, upstream, vertex, outboundOrdinal, inboundOrdinal);
            readsAs.put(view.id(), upstream);
        }

        if (pipeline.serve() instanceof ServeBlock.Use) {
            throw new IllegalArgumentException(
                    "serve block is a use-reference; resolve it to an inline serve first");
        }
        if (pipeline.serve() instanceof ServeBlock.Inline serve && serve.sync() != null) {
            List<Vertex> upstream = resolve(serve.from(), byKey, bindings, readsAs);
            List<SyncElement> sync = serve.sync();
            for (int i = 0; i < sync.size(); i++) {
                SyncElement element = sync.get(i);
                String name = SERVE_VERTEX_PREFIX + (element.id() != null ? element.id() : i);
                Vertex vertex = dag.newVertex(name, sinkVertex(bindings.sinkWriters().apply(element), sinkAck));
                connect(dag, upstream, vertex, outboundOrdinal, inboundOrdinal);
            }
        }

        return dag;
    }

    /**
     * The sink vertex for one serve.sync element: the ack-bearing sink when a sink-ack binding is present
     * (advancing a durable watermark), otherwise the no-ack sink. Both wrap the same writer factory in the
     * one generic sink adapter, pinned to total parallelism one.
     */
    private static ProcessorMetaSupplier sinkVertex(
            SupplierEx<? extends SinkWriter> writerFactory, SinkAckBinding sinkAck) {
        return sinkAck == null
                ? SinkProcessor.metaSupplier(writerFactory)
                : SinkProcessor.metaSupplier(writerFactory, sinkAck.ackFactory(), sinkAck.positionOrder());
    }

    /**
     * The vertex for one transform step. Only the linear family is in scope here: a {@code union} is
     * topology, not a transform - a passthrough vertex whose several inbound edges are the merge, so
     * the transform-port binding is never asked for one; every other stateless step (filter / map / a
     * scripted row transform) runs the one generic adapter over the port the binding supplies. A
     * stateful node ({@code nest} / {@code join}) or an unresolved {@code use:} reference is out of
     * this builder's scope and is refused; extending to them replaces the refusal, not the seam.
     */
    private static Vertex transformVertex(DAG dag, Step step, DagBindings bindings) {
        if (!(step instanceof Step.Inline inline)) {
            throw new IllegalArgumentException(
                    "transform step '" + step.id() + "' is a use-reference; resolve it to an inline step first");
        }
        TransformBody body = inline.body();
        if (body instanceof TransformBody.Nest || body instanceof TransformBody.Join) {
            throw new IllegalArgumentException(
                    "transform step '" + step.id() + "' is a stateful " + body.type()
                            + "; the linear DAG builder does not carry it");
        }
        if (body instanceof TransformBody.Union) {
            // Pinned to total parallelism one, like the transform adapter and the sink: an ordered
            // position stream a sink acks must not be re-laned by a parallel passthrough merge.
            return dag.newVertex(step.id(), ProcessorMetaSupplier.forceTotalParallelismOne(
                    ProcessorSupplier.of(Processors.mapP(FunctionEx.identity()))));
        }
        return dag.newVertex(step.id(),
                TransformProcessor.metaSupplier(bindings.transformPorts().apply(step)));
    }

    /** Resolves every reference in a streaming {@code from:} list to the producer vertices upstream. */
    private static List<Vertex> resolveClause(FromClause from, Map<String, Vertex> byKey, DagBindings bindings) {
        List<Vertex> upstream = new ArrayList<>();
        if (from instanceof FromClause.Flow flow) {
            for (FromRef ref : flow.refs()) {
                upstream.addAll(resolve(ref, byKey, bindings));
            }
        }
        return upstream;
    }

    /**
     * Resolves a {@code from:} reference to the producer vertices it names. A validated pipeline
     * always resolves, so an empty result or a key with no vertex is a builder invariant violation,
     * not a user error - it bare-throws rather than emitting a broken DAG.
     */
    private static List<Vertex> resolve(FromRef ref, Map<String, Vertex> byKey, DagBindings bindings) {
        return resolve(ref, byKey, bindings, Map.of());
    }

    /**
     * As above, but first honours the names that stand for data rather than for a vertex - today a
     * declared view, which is a terminal sink and so cannot itself be read from. Reading such a name
     * means reading what it reads, which is a fan-out from the shared upstream rather than a chain.
     */
    private static List<Vertex> resolve(FromRef ref, Map<String, Vertex> byKey, DagBindings bindings,
            Map<String, List<Vertex>> readsAs) {
        if (ref instanceof FromRef.Literal literal) {
            List<Vertex> aliased = readsAs.get(literal.ref());
            if (aliased != null) {
                return aliased;
            }
        }
        List<String> keys = bindings.upstreams().apply(ref);
        if (keys == null || keys.isEmpty()) {
            throw new IllegalStateException("reference " + ref + " resolved to no upstream vertex");
        }
        List<Vertex> vertices = new ArrayList<>();
        for (String key : keys) {
            Vertex vertex = byKey.get(key);
            if (vertex == null) {
                throw new IllegalStateException(
                        "reference " + ref + " resolved to unknown vertex '" + key + "'");
            }
            vertices.add(vertex);
        }
        return vertices;
    }

    /** Draws one edge from each upstream vertex into the destination, on fresh ordinals per endpoint. */
    private static void connect(DAG dag, List<Vertex> upstream, Vertex destination,
            Map<Vertex, Integer> outboundOrdinal, Map<Vertex, Integer> inboundOrdinal) {
        for (Vertex source : upstream) {
            int from = outboundOrdinal.merge(source, 1, Integer::sum) - 1;
            int to = inboundOrdinal.merge(destination, 1, Integer::sum) - 1;
            dag.edge(Edge.from(source, from).to(destination, to));
        }
    }
}
