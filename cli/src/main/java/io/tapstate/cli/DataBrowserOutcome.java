package io.tapstate.cli;

import java.util.List;
import java.util.Map;

/**
 * The outcomes of the three data-browser reads. Each is either what the server answered, a coded
 * refusal, or an unreachable server — sealed so the caller renders every branch without try/catch,
 * mirroring the never-throw seam.
 *
 * <p>They are gathered in one file because the three reads are one face: they share a request shape and
 * are rendered by one set of writers, and splitting them across six files would only spread that.
 */
final class DataBrowserOutcome {

    private DataBrowserOutcome() {
    }

    /** The outcome of {@code GET /api/sources/{id}/collections}. */
    sealed interface Collections {

        /** The collections the source's own database holds, in the order the connector reported them. */
        record Listed(List<String> collections) implements Collections {
        }

        record Rejected(String code, String message) implements Collections {
        }

        record Unreachable() implements Collections {
        }
    }

    /** The outcome of {@code GET /api/sources/{id}/collections/{collection}/stats}. */
    sealed interface Stats {

        /**
         * What the connector reported about one collection. Every field is nullable and a null means the
         * connector reported nothing for it, never zero: a size nobody would report and an empty
         * collection are different answers, and rendering the first as the second states it as fact.
         */
        record Reported(Long numOfRows, Long storageSize, Long avgObjSize) implements Stats {
        }

        record Rejected(String code, String message) implements Stats {
        }

        record Unreachable() implements Stats {
        }
    }

    /** The outcome of {@code POST /api/sources/{id}/collections/{collection}:find}. */
    sealed interface Find {

        /**
         * The rows read, how many the collection holds, and whether more remain past what was served.
         *
         * <p>{@code approximateTotal} is null when none was reported, which a filtered read always is.
         * {@code moreAvailable} is the one thing separating a preview of ten from a collection of ten,
         * so it is carried rather than derived — the read is one-shot and nothing else distinguishes them.
         */
        record Read(List<Map<String, Object>> rows, Long approximateTotal, boolean moreAvailable)
                implements Find {
        }

        record Rejected(String code, String message) implements Find {
        }

        record Unreachable() implements Find {
        }
    }
}
