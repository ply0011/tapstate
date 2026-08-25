package io.tapstate.spi.store;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * What one read answered with: the rows it matched, how many the collection holds if that could be
 * told cheaply, and whether the collection holds more than the rows carried here. An immutable value
 * carrying no connector-framework types.
 *
 * <p>The last field is why this type exists at all. A read is bounded, so a caller that receives ten
 * rows cannot tell a collection of ten from the first ten of a million — the two answers have the
 * same shape, and the smaller one is the one a reader believes. Nothing else in this face
 * distinguishes them: the read is one-shot, so there is no continuation token whose presence would
 * hint at it either. Carrying the fact explicitly is the whole defence against a preview being read
 * as a complete answer.
 *
 * <p>{@code approximateTotal} is nullable and null means <em>not reported</em>, never zero. It is
 * offered only when it costs nothing to know — a store's own metadata, read whole rather than
 * counted — so a filtered read leaves it null rather than paying a scan to fill it in. Being
 * metadata it is a point-in-time estimate that drifts, which is what the {@code approximate} in its
 * name is for; a surface that renders it says so too.
 */
public record DataBrowserPreview(List<Map<String, Object>> rows, Long approximateTotal, boolean moreAvailable) {

    public DataBrowserPreview {
        Objects.requireNonNull(rows, "rows");
        rows = List.copyOf(rows);
    }
}
