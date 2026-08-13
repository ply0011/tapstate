package io.tapstate.control.core;

import io.tapstate.core.common.Severity;
import io.tapstate.core.common.TapstateErrorCode;

import java.util.Set;

/** Stable coded failures for the read face over a declared source's own database. */
public enum DataBrowserError implements TapstateErrorCode {

    UNKNOWN_COLLECTION("data-browser.unknown-collection", Set.of("source", "collection"));

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
