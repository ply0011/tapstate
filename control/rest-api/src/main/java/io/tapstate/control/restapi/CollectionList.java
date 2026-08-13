package io.tapstate.control.restapi;

import java.util.List;

/**
 * The collections one source's own database holds, in the order the connector reports them — the body of
 * the data-browser listing endpoint. Wrapped in an object rather than returned as a bare array so the
 * response has room to grow without changing shape.
 */
record CollectionList(List<String> collections) {

    CollectionList {
        collections = List.copyOf(collections);
    }
}
