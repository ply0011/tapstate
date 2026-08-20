package io.tapstate.control.core;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * One registered control operation: the single source of truth for a verb the process can perform.
 *
 * <p>Fields:
 *
 * <ul>
 *   <li>{@code id} — a globally unique, dot-scoped {@code <domain>.<verb>} identifier (e.g.
 *       {@code artifact.apply}); each face derives its own name from this canonical form.
 *   <li>{@code scope} — the capability grade required to invoke it.
 *   <li>{@code audited} — whether an invocation must record an audit entry (write / admin operations do;
 *       a failed audit write means the operation does not execute).
 *   <li>{@code schema} — references into the shared schema for request and result, or {@code null}.
 *   <li>{@code description} — protocol-neutral guidance for an AI or human caller.
 *   <li>{@code exposure} — the stage at which this operation is open on each face. An absent face means
 *       "not exposed there"; the map is defensively copied and unmodifiable.
 * </ul>
 *
 * <p>Only concrete, one-to-one operations are modelled here. Face-level composition sugar (a verb that
 * chains several operations) and offline-only local computation are not operations and get no entry —
 * a face may compose registered operations, it may not invent semantics.
 */
public record Operation(
        String id,
        Scope scope,
        boolean audited,
        SchemaRef schema,
        String description,
        Map<Frontend, Maturity> exposure) {


    public Operation {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("operation id must be non-blank");
        }
        if (!isDotScopedKebab(id)) {
            throw new IllegalArgumentException("operation id must be dot-scoped <domain>.<verb>: " + id);
        }
        if (scope == null) {
            throw new IllegalArgumentException("operation scope must be set: " + id);
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("operation description must be non-blank: " + id);
        }
        EnumMap<Frontend, Maturity> copy = new EnumMap<>(Frontend.class);
        if (exposure != null) {
            for (Map.Entry<Frontend, Maturity> e : exposure.entrySet()) {
                if (e.getKey() == null || e.getValue() == null) {
                    throw new IllegalArgumentException(
                            "operation exposure must not carry a null frontend or maturity: " + id);
                }
                copy.put(e.getKey(), e.getValue());
            }
        }
        exposure = Collections.unmodifiableMap(copy);
    }

    /**
     * Whether {@code id} is {@code <domain>.<verb>}, every segment lower kebab. The same shape and the
     * same spelling rule an error code uses, deliberately: they name the same domains, so a domain of two
     * words must not be spelled one way here and another there.
     *
     * <p>Read in one pass rather than matched against a pattern. The pattern this replaces held one
     * repetition inside another - dash-joined words inside dot-joined segments - and this engine recurses
     * once per iteration of a repeated group, so a long enough id ended the check with a
     * StackOverflowError: an Error, not the refusal the constructor promises, which a caller can neither
     * catch as one nor read a reason from. Measured before the change: about 5,000 dash-joined words, or
     * about 10,000 segments. A single pass has no such bound, and each rule below is one line.
     *
     * <p>The character tests are written out rather than taken from {@code Character}, whose notions of
     * letter and digit reach beyond ASCII. An id is ASCII by construction, and a check that quietly
     * admitted more would widen the contract without anybody choosing to.
     */
    private static boolean isDotScopedKebab(String id) {
        boolean dotted = false;
        // A segment opens where the previous character was a dot, so starting there reads the first
        // character of the id under the same rule as the first character of any later segment.
        char previous = '.';
        for (int i = 0; i < id.length(); i++) {
            char current = id.charAt(i);
            boolean opensSegment = previous == '.';
            if (current == '.') {
                if (opensSegment || previous == '-') {
                    return false;
                }
                dotted = true;
            } else if (current == '-') {
                if (opensSegment || previous == '-') {
                    return false;
                }
            } else if (!(opensSegment ? isLower(current) : isLowerOrDigit(current))) {
                return false;
            }
            previous = current;
        }
        // A dot is what makes it scoped; a trailing dot or dash leaves a segment or a word unfinished.
        return dotted && previous != '.' && previous != '-';
    }

    private static boolean isLower(char c) {
        return c >= 'a' && c <= 'z';
    }

    private static boolean isLowerOrDigit(char c) {
        return isLower(c) || (c >= '0' && c <= '9');
    }

    /** Compatibility constructor for operations that do not yet need specialized caller guidance. */
    public Operation(String id, Scope scope, boolean audited, SchemaRef schema, Map<Frontend, Maturity> exposure) {
        this(id, scope, audited, schema, id, exposure);
    }
}
