package io.tapstate.tools.catalog.assembler;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.tapstate.core.catalog.CatalogJson;

/**
 * Names the fields that differ between a checked-in catalog entry and the regenerated one.
 *
 * <p>The byte-lock compares whole files, which is the right comparison - the artifact is locked byte
 * for byte - but the wrong report. A regenerated entry is a few hundred characters of JSON on one
 * line per field, so "these two strings differ" leaves the reader diffing by eye to find out whether
 * what moved was a mode, a sha, or a config default. Those have completely different meanings: a
 * changed sha is a routine re-pin, a changed mode is a capability claim.
 *
 * <p>This does not replace the equality assertion, it decorates it. The full comparison still decides
 * whether the build is red; this only says where to look first.
 */
final class CatalogEntryDrift {

    /** Nested objects worth naming a field inside, rather than reporting the whole object as changed. */
    private static final String PROVENANCE = "provenance";

    private CatalogEntryDrift() {
    }

    /**
     * A trailing clause naming the differing fields, or an empty string when the two agree.
     *
     * <p>Never throws. This runs on the failure path of a gate, and content that cannot be parsed is
     * exactly what a truncated or half-written artifact looks like - turning that into an exception
     * would replace a legible drift report with a stack trace from the reporter.
     */
    static String describe(String checkedIn, String regenerated) {
        if (Objects.equals(checkedIn, regenerated)) {
            return "";
        }
        Map<String, Object> before = asMap(checkedIn);
        Map<String, Object> after = asMap(regenerated);
        if (before == null || after == null) {
            return " - one of the two is not a JSON object, so the differing fields cannot be named; "
                    + "compare the files directly";
        }
        List<String> differences = new ArrayList<>();
        for (String field : union(before, after)) {
            Object left = before.get(field);
            Object right = after.get(field);
            if (Objects.equals(left, right)) {
                continue;
            }
            if (PROVENANCE.equals(field) && left instanceof Map && right instanceof Map) {
                differences.addAll(nested(PROVENANCE, castToMap(left), castToMap(right)));
            } else {
                differences.add(describeField(field, left, right));
            }
        }
        return differences.isEmpty()
                // Equal field by field yet unequal as text: whitespace, key order, or number
                // formatting. Worth saying, because "fields that differ: none" reads like a bug here.
                ? " - no field differs; the two agree in content but not byte for byte (ordering, "
                        + "spacing, or number formatting)"
                : " - fields that differ: " + String.join(", ", differences);
    }

    private static List<String> nested(String prefix, Map<String, Object> left, Map<String, Object> right) {
        List<String> differences = new ArrayList<>();
        for (String field : union(left, right)) {
            Object a = left.get(field);
            Object b = right.get(field);
            if (!Objects.equals(a, b)) {
                differences.add(describeField(prefix + "." + field, a, b));
            }
        }
        return differences;
    }

    private static String describeField(String field, Object checkedIn, Object regenerated) {
        return field + " (checked-in " + render(checkedIn) + ", regenerated " + render(regenerated) + ")";
    }

    private static String render(Object value) {
        return value == null ? "absent" : String.valueOf(value);
    }

    private static Set<String> union(Map<String, Object> left, Map<String, Object> right) {
        Set<String> fields = new LinkedHashSet<>(left.keySet());
        fields.addAll(right.keySet());
        return fields;
    }

    private static Map<String, Object> asMap(String json) {
        try {
            Object parsed = CatalogJson.parse(json);
            return parsed instanceof Map ? castToMap(parsed) : null;
        } catch (RuntimeException malformed) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castToMap(Object value) {
        return (Map<String, Object>) value;
    }
}
