package io.tapstate.cli;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One call typed into the read shell, already parsed. The shell is a small language of its own rather
 * than more verbs, because what it names is a place in the data — {@code <source>.<collection>} — and a
 * verb table has no way to spell that. It is the same language a reader arrives with from a database
 * shell, which is the whole reason for using it here.
 *
 * <p>Three calls, and nothing else:
 *
 * <pre>
 *   show collections [&lt;source&gt;]
 *   &lt;source&gt;.&lt;collection&gt;.stats()
 *   &lt;source&gt;.&lt;collection&gt;.find([&lt;filter&gt;]) [.sort({field: "f", dir: "asc"})] [.limit(n)]
 * </pre>
 *
 * <p>The filter is written the way a reader writes one in a database shell — {@code {status: "Paid"}},
 * {@code {age: {$gt: 12}}}, {@code $or} for a choice — because that is the syntax they already know.
 * {@link MongoShellFilter} reads it and rebuilds it as Tapstate's own vocabulary before it goes
 * anywhere, so the shell accepts that syntax without forwarding it: what leaves this process says only
 * what the vocabulary can say, and the operators that evaluate code inside a database have no term to
 * be translated into.
 *
 * <p>{@link #parse} answers null for a line that is not one of these at all, so the verb table still
 * gets every line this shell has no claim on. A line that is plainly meant as one and cannot be read
 * comes back as {@link Malformed} instead of falling through, because falling through would answer a
 * misspelt call with a complaint about an unknown verb.
 */
sealed interface DataBrowserCall {

    /** {@code show collections [<source>]} — a null source means every declared one. */
    record Collections(String sourceId) implements DataBrowserCall {
    }

    /** {@code <source>.<collection>.stats()}. */
    record Stats(String sourceId, String collection) implements DataBrowserCall {
    }

    /**
     * {@code <source>.<collection>.find(...)}. {@code filter} is the parsed literal, handed on as it was
     * written: the vocabulary is checked by the control plane, once, rather than by each surface that
     * offers it. A null filter, order or size each mean the request did not ask, which is not the same
     * as asking for a default.
     */
    record Find(String sourceId, String collection, Object filter, Order sort, Integer limit)
            implements DataBrowserCall {
    }

    /** The order asked for, in the two words this face publishes. Validated by the control plane. */
    record Order(String field, String dir) {
    }

    /**
     * The operands of one of the two live views: {@code watch <ns> [filter]} / {@code tail <ns> [filter]}.
     * They are read here rather than by each command because they name the same place in the data and
     * take the same filter as {@code find} does — a second reader for them is a second grammar, and the
     * two would drift a literal at a time.
     */
    record Live(String verb, String sourceId, String collection, Object filter) implements DataBrowserCall {
    }

    /** A line this shell claims and cannot read, with the reason to print. */
    record Malformed(String reason) implements DataBrowserCall {
    }

    /**
     * Reads one live view's operands — a namespace and an optional filter. Never answers null: the verb
     * has already been matched by the caller, so a line it cannot read is that verb written wrongly, and
     * falling through would answer it with a complaint about an unknown verb.
     */
    static DataBrowserCall parseLive(String verb, String operands) {
        String trimmed = operands == null ? "" : operands.trim();
        if (trimmed.isEmpty()) {
            return new Malformed(verb + " takes a namespace, as in `" + verb + " views.orders`");
        }
        int space = trimmed.indexOf(' ');
        String namespace = space < 0 ? trimmed : trimmed.substring(0, space);
        int dot = namespace.indexOf('.');
        if (dot <= 0 || dot == namespace.length() - 1) {
            return new Malformed("`" + namespace + "` is not a `<source>.<collection>`; "
                    + verb + " watches one collection, as in `" + verb + " views.orders`");
        }
        try {
            // The filter is the shell's own syntax, rebuilt as the vocabulary here, exactly as a read
            // does it — so what leaves this process says only what the vocabulary can say.
            Object filter = space < 0 ? null
                    : MongoShellFilter.translate(new Reader(trimmed, space).value());
            return new Live(verb, namespace.substring(0, dot), namespace.substring(dot + 1), filter);
        } catch (Unreadable unreadable) {
            return new Malformed(unreadable.getMessage());
        }
    }

    /** Parses one typed line, or answers null when it is not a read-shell line at all. */
    static DataBrowserCall parse(String line) {
        String trimmed = line == null ? "" : line.trim();
        if (trimmed.startsWith("show ") || trimmed.equals("show")) {
            return parseShow(trimmed);
        }
        return parseNamespaceCall(trimmed);
    }

    /** {@code show collections [<source>]}, and a reason for any other {@code show}. */
    private static DataBrowserCall parseShow(String line) {
        List<String> words = new ArrayList<>(List.of(line.split("\\s+")));
        if (words.size() == 2 && words.get(1).equals("collections")) {
            return new Collections(null);
        }
        if (words.size() == 3 && words.get(1).equals("collections")) {
            return new Collections(words.get(2));
        }
        return new Malformed("the only listing this shell shows is `show collections [<source>]`");
    }

    /**
     * A {@code <source>.<collection>.<verb>(...)} line, or null when the line has no such shape.
     *
     * <p>The source is the first dotted segment and the collection is everything between it and the
     * verb, because a source id is one artifact id while a collection name may itself hold dots. Split
     * the other way round and {@code views.a.b.stats()} would look up a source called {@code views.a},
     * which resolves to nothing and blames the half of the line that was right.
     */
    private static DataBrowserCall parseNamespaceCall(String line) {
        int call = indexOfCall(line);
        if (call < 0) {
            return null;
        }
        String namespace = line.substring(0, call);
        int dot = namespace.indexOf('.');
        if (dot <= 0 || dot == namespace.length() - 1) {
            return null;
        }
        String sourceId = namespace.substring(0, dot);
        String collection = namespace.substring(dot + 1);
        Reader reader = new Reader(line, call + 1);
        try {
            String verb = reader.word();
            return switch (verb) {
                case "stats" -> parseStats(reader, sourceId, collection);
                case "find" -> parseFind(reader, sourceId, collection);
                default -> new Malformed("`" + verb + "` is not a read: this shell offers `find(...)`, "
                        + "`stats()`, and `show collections`");
            };
        } catch (Unreadable unreadable) {
            return new Malformed(unreadable.getMessage());
        }
    }

    /**
     * Where the verb starts in a namespace call — the dot before the first {@code (} — or -1 when the
     * line is not shaped like one at all. A line with no bracket is somebody else's; so is one whose
     * namespace holds a space, which is how a verb and its operand look.
     */
    private static int indexOfCall(String line) {
        int bracket = line.indexOf('(');
        if (bracket < 0) {
            return -1;
        }
        int dot = line.lastIndexOf('.', bracket);
        if (dot <= 0) {
            return -1;
        }
        return line.substring(0, dot).indexOf(' ') < 0 && line.substring(0, dot).indexOf('.') > 0 ? dot : -1;
    }

    private static DataBrowserCall parseStats(Reader reader, String sourceId, String collection) {
        reader.expect('(');
        reader.expect(')');
        reader.expectEnd("stats() takes nothing");
        return new Stats(sourceId, collection);
    }

    private static DataBrowserCall parseFind(Reader reader, String sourceId, String collection) {
        reader.expect('(');
        // Read as the shell's own syntax, then rebuilt as the vocabulary before it goes anywhere. What
        // reaches the wire is never the document that was typed.
        Object filter = reader.peek() == ')' ? null : MongoShellFilter.translate(reader.value());
        reader.expect(')');
        Order sort = null;
        Integer limit = null;
        // Chained in either order: neither reads as the primary one, and refusing the order a user
        // happened to type would refuse a line that is plainly correct.
        while (reader.peek() == '.') {
            reader.expect('.');
            String chained = reader.word();
            switch (chained) {
                case "sort" -> {
                    reader.expect('(');
                    sort = order(reader.value());
                    reader.expect(')');
                }
                case "limit" -> {
                    reader.expect('(');
                    limit = reader.wholeNumber();
                    reader.expect(')');
                }
                default -> throw new Unreadable("`" + chained
                        + "` cannot be chained onto a read; the ones that can are `sort` and `limit`");
            }
        }
        reader.expectEnd("a read ends after its last chained call");
        return new Find(sourceId, collection, filter, sort, limit);
    }

    private static Order order(Object written) {
        if (!(written instanceof Map<?, ?> map)) {
            throw new Unreadable("sort takes an order, as in `.sort({field: \"total\", dir: \"desc\"})`");
        }
        return new Order(text(map.get("field")), text(map.get("dir")));
    }

    private static String text(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    /** A line this shell claims and cannot read; carried out of the reader as its reason. */
    final class Unreadable extends RuntimeException {

        Unreadable(String reason) {
            super(reason);
        }
    }

    /**
     * The literal reader: a JavaScript object subset — objects, arrays, text in either quote, whole and
     * decimal numbers, {@code true} / {@code false} / {@code null}. Deliberately no more than that. What
     * it will not read is what a database shell's own literals let a reader reach for, and every one of
     * those belongs to a query language this face does not forward.
     */
    final class Reader {

        private final String source;
        private int at;

        Reader(String source, int at) {
            this.source = source;
            this.at = at;
        }

        /** The next non-blank character without consuming it, or {@code 0} at the end of the line. */
        char peek() {
            skipBlanks();
            return at < source.length() ? source.charAt(at) : 0;
        }

        void expect(char expected) {
            if (peek() != expected) {
                throw new Unreadable("expected `" + expected + "` at position " + (at + 1)
                        + " of `" + source + "`");
            }
            at++;
        }

        void expectEnd(String what) {
            if (peek() != 0) {
                throw new Unreadable(what + ", and `" + source.substring(at) + "` follows it");
            }
        }

        /**
         * A bare word: a verb, a key, or one of the three literals. {@code $} is a word character here
         * because the shell's operators are spelt with one; refusing it in the lexer would answer
         * {@code {$where: ...}} with a complaint about punctuation rather than with the reason it is not
         * taken, and the reason is the whole of what a reader needs.
         */
        String word() {
            skipBlanks();
            int start = at;
            while (at < source.length() && (Character.isLetterOrDigit(source.charAt(at))
                    || source.charAt(at) == '_' || source.charAt(at) == '-' || source.charAt(at) == '$')) {
                at++;
            }
            if (start == at) {
                throw new Unreadable("expected a name at position " + (at + 1) + " of `" + source + "`");
            }
            return source.substring(start, at);
        }

        int wholeNumber() {
            Object value = value();
            if (!(value instanceof Long whole) || whole > Integer.MAX_VALUE || whole < Integer.MIN_VALUE) {
                throw new Unreadable("limit takes a whole number of rows, as in `.limit(25)`");
            }
            return whole.intValue();
        }

        Object value() {
            char next = peek();
            return switch (next) {
                case '{' -> object();
                case '[' -> array();
                case '"', '\'' -> text(next);
                case 0 -> throw new Unreadable("`" + source + "` ends where a value was expected");
                default -> Character.isDigit(next) || next == '-' ? number() : keyword();
            };
        }

        private Map<String, Object> object() {
            expect('{');
            Map<String, Object> fields = new LinkedHashMap<>();
            if (peek() == '}') {
                at++;
                return fields;
            }
            while (true) {
                char quoted = peek();
                String key = quoted == '"' || quoted == '\'' ? text(quoted) : word();
                expect(':');
                fields.put(key, value());
                if (peek() == ',') {
                    at++;
                    continue;
                }
                expect('}');
                return fields;
            }
        }

        private List<Object> array() {
            expect('[');
            List<Object> items = new ArrayList<>();
            if (peek() == ']') {
                at++;
                return items;
            }
            while (true) {
                items.add(value());
                if (peek() == ',') {
                    at++;
                    continue;
                }
                expect(']');
                return items;
            }
        }

        private String text(char quote) {
            expect(quote);
            StringBuilder read = new StringBuilder();
            while (at < source.length() && source.charAt(at) != quote) {
                char character = source.charAt(at++);
                if (character == '\\' && at < source.length()) {
                    appendEscaped(read, source.charAt(at++));
                    continue;
                }
                read.append(character);
            }
            if (at >= source.length()) {
                throw new Unreadable("`" + source + "` ends inside a quoted value");
            }
            at++;
            return read.toString();
        }

        /**
         * The escapes this reader defines, plus what to do with one it does not.
         *
         * <p>An escape it does not define is not addressed to it. A field name says a dot belongs to
         * the name rather than stepping into a document by writing {@code \.}, and that spelling is
         * read further on, by whoever turns a name into the steps it is made of. Dropping the
         * backslash here hands that reader the other spelling instead -- a step -- which is a filter
         * that matches nothing and reports nothing wrong, and an order that sorts every row equal.
         * Passing the pair through leaves the question where it can be answered.
         *
         * <p>The three that end a run of text are therefore listed rather than left to fall through:
         * a quote whose backslash survived would close the value it was written to keep open.
         */
        private static void appendEscaped(StringBuilder read, char character) {
            switch (character) {
                case 'n' -> read.append('\n');
                case 't' -> read.append('\t');
                case 'r' -> read.append('\r');
                case '\\' -> read.append('\\');
                case '"' -> read.append('"');
                case '\'' -> read.append('\'');
                default -> read.append('\\').append(character);
            }
        }

        private Object number() {
            int start = at;
            if (peek() == '-') {
                at++;
            }
            boolean fractional = false;
            while (at < source.length()
                    && (Character.isDigit(source.charAt(at)) || source.charAt(at) == '.')) {
                fractional |= source.charAt(at) == '.';
                at++;
            }
            String written = source.substring(start, at);
            try {
                // Branched rather than written as one conditional expression: a conditional whose two
                // arms are Double and Long promotes both to double, so every whole number would come
                // back fractional — and a fractional row count is refused by the one call that takes one.
                if (fractional) {
                    return Double.valueOf(written);
                }
                return Long.valueOf(written);
            } catch (NumberFormatException notANumber) {
                throw new Unreadable("`" + written + "` is not a number");
            }
        }

        private Object keyword() {
            String written = word();
            return switch (written) {
                case "true" -> Boolean.TRUE;
                case "false" -> Boolean.FALSE;
                case "null" -> null;
                // Quoting is not decoration here: an unquoted word is how a database shell spells a
                // reference to something on the server, and this literal has no such thing in it.
                default -> throw new Unreadable("`" + written + "` needs quoting to be text");
            };
        }

        private void skipBlanks() {
            while (at < source.length() && Character.isWhitespace(source.charAt(at))) {
                at++;
            }
        }
    }
}
