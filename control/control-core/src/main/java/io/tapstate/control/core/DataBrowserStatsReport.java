package io.tapstate.control.core;

import io.tapstate.spi.store.DataBrowserTableInfo;

/**
 * The surface-facing report of one collection's size: the control ring's own projection of the
 * storage-port {@link DataBrowserTableInfo}, so the HTTP, CLI and agent faces render a control-ring type
 * and never reach into the storage ports. An immutable value.
 *
 * <p>Every field is nullable and a null means the connector reported nothing for it — not zero. A
 * collection whose size a connector will not report and an empty collection are different answers, and
 * collapsing them to zero states the second one as fact. {@code numOfRows} is read off the store's own
 * metadata, so it is a point-in-time estimate that drifts rather than a counted total; a surface that
 * renders it says so. {@code storageSize} and {@code avgObjSize} are bytes.
 */
public record DataBrowserStatsReport(Long numOfRows, Long storageSize, Long avgObjSize) {

    /** Projects a storage-port table info onto the surface report. */
    public static DataBrowserStatsReport from(DataBrowserTableInfo info) {
        return new DataBrowserStatsReport(info.numOfRows(), info.storageSize(), info.avgObjSize());
    }
}
