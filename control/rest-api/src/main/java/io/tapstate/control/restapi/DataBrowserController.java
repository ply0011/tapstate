package io.tapstate.control.restapi;

import io.tapstate.control.core.DataBrowserPreviewReport;
import io.tapstate.control.core.DataBrowserService;
import io.tapstate.control.core.DataBrowserSortOrder;
import io.tapstate.control.core.DataBrowserStatsReport;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Locale;
import java.util.Objects;

/**
 * The three data-browser verbs projected onto HTTP: list a declared source's collections, read one
 * collection's rows, report one collection's size. Each drives the control-core service, which resolves
 * the source to the connection its reads run on and drives the whitelisted runtime probe. A thin
 * projection with no business logic of its own; the service speaks control-ring types, so this face never
 * reaches into the storage ports.
 *
 * <p>They read through to the connector and persist nothing, not even the result, so all three are
 * read-scoped and unaudited — that is what separates them from the two connection probes, which look
 * similar but store what they found. The grade is the registry's, not the HTTP method's: the read is
 * posted because it carries a request body, not because it changes anything.
 *
 * <p>Every call stands alone. The read is one-shot, so no continuation state travels in either direction:
 * nothing in the request names a position to resume from, and nothing in the response offers one. What
 * the response does carry is whether more rows remain — the only thing separating a preview of ten from a
 * collection of ten.
 *
 * <p>Which database a read reaches is settled by the source's own connection. Neither path nor body has a
 * field for one, so nothing a caller sends can move a read off the connection it was resolved from.
 */
@RestController
class DataBrowserController {

    private final DataBrowserService browser;

    DataBrowserController(DataBrowserService browser) {
        this.browser = Objects.requireNonNull(browser, "browser");
    }

    @Verb("data-browser.collections")
    @GetMapping("/sources/{sourceId}/collections")
    CollectionList collections(@PathVariable("sourceId") String sourceId) {
        // The path variable is named explicitly: this build compiles without -parameters, so an inferred
        // name would not resolve at runtime.
        return new CollectionList(browser.collections(sourceId));
    }

    @Verb("data-browser.stats")
    @GetMapping("/sources/{sourceId}/collections/{collection}/stats")
    DataBrowserStatsReport stats(
            @PathVariable("sourceId") String sourceId, @PathVariable("collection") String collection) {
        return browser.stats(sourceId, collection);
    }

    @Verb("data-browser.find")
    @PostMapping("/sources/{sourceId}/collections/{collection}:find")
    DataBrowserPreviewReport find(
            @PathVariable("sourceId") String sourceId,
            @PathVariable("collection") String collection,
            @RequestBody(required = false) DataBrowserFindRequest request) {
        // A body-less read is the whole request shape left unasked-for, which is a valid request: every
        // field is optional and an absent one takes the control plane's own answer.
        DataBrowserFindRequest body =
                request == null ? new DataBrowserFindRequest(null, null, null) : request;
        return browser.find(sourceId, collection, body.filter(), order(body.sort()), body.limit());
    }

    /**
     * The control-ring order for a requested one, or null when none was asked for — which leaves the order
     * to the database rather than imposing a default.
     *
     * <p>A direction that is neither {@code asc} nor {@code desc} is refused here as a coded 400: it is
     * client-attributable input, and the alternative — quietly reading it as one of the two — would return
     * rows in an order the caller did not ask for and cannot tell apart from the one they did.
     */
    private static DataBrowserSortOrder order(DataBrowserFindRequest.Sort sort) {
        if (sort == null) {
            return null;
        }
        MalformedRequest.requireText(sort.field(), "a `sort` needs the `field` to order by");
        MalformedRequest.requireText(sort.dir(), "a `sort` needs a `dir` of `asc` or `desc`");
        return new DataBrowserSortOrder(sort.field(), switch (sort.dir().toLowerCase(Locale.ROOT)) {
            case "asc" -> DataBrowserSortOrder.Direction.ASC;
            case "desc" -> DataBrowserSortOrder.Direction.DESC;
            default -> throw MalformedRequest.rejecting(
                    "a `sort` direction must be `asc` or `desc`, not `" + sort.dir() + "`", null);
        });
    }
}
