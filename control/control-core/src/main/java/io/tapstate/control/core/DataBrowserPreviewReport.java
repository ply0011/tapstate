package io.tapstate.control.core;

import io.tapstate.spi.store.DataBrowserPreview;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The surface-facing report of one read: the control ring's own projection of the storage-port
 * {@link DataBrowserPreview}, so the HTTP, CLI and agent faces render a control-ring type and never reach
 * into the storage ports. An immutable value.
 *
 * <p>{@code moreAvailable} is why this carries three fields rather than one. A read is bounded, so a
 * caller that receives ten rows cannot tell a collection of ten from the first ten of a million — the two
 * answers have the same shape, and the smaller one is the one a reader believes. Nothing else here
 * distinguishes them: the read is one-shot, so there is no continuation token whose presence would hint
 * at it. Carrying the fact explicitly is the whole defence against a preview being read as a complete
 * answer, which is why every surface renders it rather than treating it as a footnote.
 *
 * <p>{@code approximateTotal} is nullable and null means <em>not reported</em>, never zero — it is offered
 * only when it costs nothing to know, so a filtered read leaves it null rather than paying a scan.
 */
public record DataBrowserPreviewReport(
        List<Map<String, Object>> rows, Long approximateTotal, boolean moreAvailable) {

    public DataBrowserPreviewReport {
        Objects.requireNonNull(rows, "rows");
        rows = List.copyOf(rows);
    }

    /** Projects a storage-port preview onto the surface report. */
    public static DataBrowserPreviewReport from(DataBrowserPreview preview) {
        return new DataBrowserPreviewReport(
                preview.rows(), preview.approximateTotal(), preview.moreAvailable());
    }
}
