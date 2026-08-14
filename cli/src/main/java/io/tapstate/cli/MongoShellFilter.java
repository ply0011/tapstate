package io.tapstate.cli;

import io.tapstate.cli.DataBrowserCall.Unreadable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads a filter written the way a reader writes one in a database shell, and answers Tapstate's own
 * filter vocabulary. The shell's syntax is what a reader already knows — a field beside its value,
 * {@code {$gt: 12}} for a comparison, {@code $or} for a choice — so that is what this face accepts.
 *
 * <p>It accepts that syntax; it does not forward it. Every condition is read here and rebuilt as a
 * vocabulary term, so what leaves this process says only what the vocabulary can say. The difference is
 * not academic: a forwarded document carries whatever the store's query language can express, and what
 * those languages can express includes evaluating code inside the database. Nothing here can produce
 * that, because there is no term in the vocabulary that means it.
 *
 * <p>So the syntax is a superset of what is accepted, and the gap has to be visible. Every refusal names
 * the operator it would not take and what to write instead — a filter language whose limits are only
 * discoverable by hitting them is one a reader gives up on.
 */
final class MongoShellFilter {

    /** How each accepted operator is written in the shell, in the order a refusal lists them. */
    private static final Map<String, String> OPERATORS = new LinkedHashMap<>();

    static {
        OPERATORS.put("$eq", "eq");
        OPERATORS.put("$ne", "ne");
        OPERATORS.put("$gt", "gt");
        OPERATORS.put("$gte", "gte");
        OPERATORS.put("$lt", "lt");
        OPERATORS.put("$lte", "lte");
        OPERATORS.put("$in", "in");
        OPERATORS.put("$exists", "exists");
        // Tapstate's own, with no counterpart in the shell: a literal search. The shell spells substring
        // matching as a pattern, and a pattern is the one thing this vocabulary will not carry, so
        // offering nothing here would leave text unsearchable rather than merely narrowed.
        OPERATORS.put("$contains", "contains");
    }

    private MongoShellFilter() {
    }

    /** The vocabulary filter for one written document, or null when none was written. */
    static Object translate(Object written) {
        if (written == null) {
            return null;
        }
        if (!(written instanceof Map<?, ?> document)) {
            throw new Unreadable("a filter is a document, as in `{status: \"Paid\"}`");
        }
        if (document.isEmpty()) {
            // Every row is what an omitted filter already asks for, so this is a second spelling of one
            // request - and the likelier reading is that a condition was meant and got lost.
            throw new Unreadable("`{}` matches every row; leave the filter out to read them all");
        }
        List<Object> conjuncts = new ArrayList<>();
        document.forEach((key, value) -> {
            String name = String.valueOf(key);
            switch (name) {
                // Side by side in one document already means "and", so an explicit one folds into the
                // same list rather than nesting: `{a: 1, $and: [{b: 2}]}` and `{a: 1, b: 2}` are the
                // same request and must not come out as two different shapes.
                case "$and" -> members(name, value).forEach(member -> conjuncts.addAll(flatten(member)));
                case "$or" -> conjuncts.add(alternative(members(name, value)));
                default -> {
                    if (name.startsWith("$")) {
                        throw new Unreadable(refusal(name));
                    }
                    conjuncts.add(term(name, value));
                }
            }
        });
        if (conjuncts.size() == 1) {
            return conjuncts.get(0);
        }
        return Map.of("all", conjuncts);
    }

    /** One translated member as the conjuncts it contributes, unwrapping a conjunction it came back as. */
    private static List<Object> flatten(Object member) {
        Object translated = translate(member);
        if (translated instanceof Map<?, ?> map && map.get("all") instanceof List<?> nested) {
            return new ArrayList<>(nested);
        }
        return List.of(translated);
    }

    /** {@code $or}'s members, each of which must be one term: the vocabulary's alternatives hold terms. */
    private static Object alternative(List<?> members) {
        List<Object> terms = new ArrayList<>(members.size());
        for (Object member : members) {
            Object translated = translate(member);
            if (translated instanceof Map<?, ?> map && (map.containsKey("all") || map.containsKey("any"))) {
                throw new Unreadable("each choice in `$or` is one condition; "
                        + "a choice between groups of conditions is more than this filter can express");
            }
            terms.add(translated);
        }
        return Map.of("any", terms);
    }

    private static List<?> members(String connective, Object value) {
        if (!(value instanceof List<?> written) || written.isEmpty()) {
            throw new Unreadable("`" + connective + "` takes a non-empty list of conditions");
        }
        return written;
    }

    /** One field's condition: a bare value is equality, a document is the one operator it names. */
    private static Map<String, Object> term(String field, Object value) {
        if (!(value instanceof Map<?, ?> applied)) {
            return vocabulary(field, "eq", value);
        }
        if (applied.size() != 1) {
            throw new Unreadable("`" + field + "` is tested by " + applied.size()
                    + " operators at once; this filter applies one per condition, so write them as "
                    + "separate entries of `$and`");
        }
        Map.Entry<?, ?> only = applied.entrySet().iterator().next();
        String operator = String.valueOf(only.getKey());
        String spelling = OPERATORS.get(operator);
        if (spelling == null) {
            throw new Unreadable(refusal(operator));
        }
        return vocabulary(field, spelling, only.getValue());
    }

    private static Map<String, Object> vocabulary(String field, String operator, Object value) {
        Map<String, Object> term = new LinkedHashMap<>();
        term.put("field", field);
        term.put("op", operator);
        term.put("value", value);
        return term;
    }

    /**
     * Why one operator is not taken, and what to write instead. Two get their own sentence because the
     * generic list answers the wrong question for them: a pattern has a replacement to name, and the
     * operators that evaluate an expression are refused as a decision rather than as an omission.
     */
    private static String refusal(String operator) {
        String known = String.join(" ", OPERATORS.keySet());
        return switch (operator.toLowerCase(Locale.ROOT)) {
            case "$regex" -> "`$regex` is not taken here: a filter carries values to find, not patterns to "
                    + "run. For text, `{field: {$contains: \"...\"}}` finds it literally.";
            case "$where", "$expr", "$function", "$accumulator" -> "`" + operator
                    + "` evaluates an expression inside the database, which this face does not do. "
                    + "Conditions available: " + known;
            default -> "`" + operator + "` is not one of the conditions this filter takes: " + known
                    + ", plus `$and` and `$or`";
        };
    }
}
