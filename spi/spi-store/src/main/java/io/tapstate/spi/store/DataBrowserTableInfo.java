package io.tapstate.spi.store;

/**
 * What a connector reports about one collection: how many rows it holds and how large it is. An
 * immutable value carrying no connector-framework types.
 *
 * <p>Every field is nullable, and a null means the connector reported nothing for it — not zero. The
 * distinction is the point: a collection whose size a connector will not report and an empty
 * collection are different answers, and collapsing them to zero states the second one as fact.
 *
 * <p>{@code numOfRows} is a point-in-time count read off the store's own metadata, so it is an
 * estimate that drifts rather than a counted total. {@code storageSize} and {@code avgObjSize} are
 * bytes.
 */
public record DataBrowserTableInfo(Long numOfRows, Long storageSize, Long avgObjSize) {
}
