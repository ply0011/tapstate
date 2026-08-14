package io.tapstate.control.restapi;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.tapstate.control.core.DataBrowserCollection;
import java.util.List;

/**
 * The collections one source's own database holds, in the order the connector reports them — the body of
 * the data-browser listing endpoint. Wrapped in an object rather than returned as a bare array so the
 * response has room to grow without changing shape.
 */
record CollectionList(List<Entry> collections) {

    CollectionList {
        collections = List.copyOf(collections);
    }

    /** The listing for one source, projected entry by entry. */
    static CollectionList of(List<DataBrowserCollection> listed) {
        return new CollectionList(listed.stream().map(Entry::of).toList());
    }

    /**
     * One collection: its name, the class of collection it is, and — when anything answered them — the
     * fields discovery found and the text whoever declared it wrote.
     *
     * <p>An unanswered one is left out of the body entirely rather than sent as an empty value, which
     * is what {@code NON_NULL} is doing here and the one thing about this record that is load-bearing.
     * A caller reading {@code "fields": []} has been told the collection has no fields, and one reading
     * {@code "description": ""} has been told somebody described it as nothing; neither is what "nobody
     * has looked" and "nobody declared it" mean, and an absent key is the only form that says so. The
     * caller's own way forward differs by which it is — with no fields it reads the first page to see
     * the shape, which it would have no reason to do if told the collection had none.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record Entry(String name, String kind, List<String> fields, String description) {

        static Entry of(DataBrowserCollection listed) {
            return new Entry(listed.name(), listed.kind(), listed.fields(), listed.description());
        }
    }
}
