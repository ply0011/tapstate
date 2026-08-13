package io.tapstate.control.core;

import io.tapstate.core.common.Severity;
import io.tapstate.core.common.TapstateErrorCode;

import java.util.Set;

/** Stable coded failures for the read face over a declared source's own database. */
public enum DataBrowserError implements TapstateErrorCode {

    /** The collection asked for is not one the source's own database holds. */
    UNKNOWN_COLLECTION("data-browser.unknown-collection", Set.of("source", "collection")),

    /**
     * The read asked for a number of rows this face will not serve — more than the cap, or none at
     * all. {@code limit} is what was asked for; {@code max} is the cap. The cap is what keeps a
     * preview a preview: this face reads whole rows into memory in one shot and hands them to a
     * terminal, so an unbounded request is a way to pull a collection through it. Below one there is
     * no read to serve either — the answer would be no rows and "there is more", which reads as an
     * empty collection with a contradiction attached.
     */
    INVALID_LIMIT("data-browser.invalid-limit", Set.of("limit", "max"));

    private final String code;
    private final Set<String> placeholders;

    DataBrowserError(String code, Set<String> placeholders) {
        this.code = code;
        this.placeholders = placeholders;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public Severity severity() {
        return Severity.ERROR;
    }

    @Override
    public Set<String> placeholders() {
        return placeholders;
    }
}
