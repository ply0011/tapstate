package io.tapstate.cli;

import java.util.List;
import java.util.Map;

/**
 * Deterministic JSON writer for an ordered tree of {@code Map<String,?>} / {@code List<?>} /
 * {@code String} / {@code Number} / {@code Boolean} / {@code null}. Two-space indented, one element
 * per line, empty collections inline, no trailing newline (the caller decides line endings). The
 * surface ring carries no JSON library (rule R6), so this is hand-written.
 */
final class JsonOut {

    private JsonOut() {
    }

    static String write(Object root) {
        StringBuilder sb = new StringBuilder();
        writeValue(sb, root, 0);
        return sb.toString();
    }

    /**
     * The same tree on one line, for output a reader scans down rather than reads: a preview of rows is
     * a list of things being compared to each other, and the indented form buries each row in the space
     * between its own fields.
     */
    static String compact(Object root) {
        StringBuilder sb = new StringBuilder();
        writeCompact(sb, root);
        return sb.toString();
    }

    private static void writeCompact(StringBuilder sb, Object value) {
        if (value instanceof Map<?, ?> map) {
            sb.append('{');
            int i = 0;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                sb.append('"').append(escape(String.valueOf(entry.getKey()))).append("\": ");
                writeCompact(sb, entry.getValue());
                if (++i < map.size()) {
                    sb.append(", ");
                }
            }
            sb.append('}');
        } else if (value instanceof List<?> list) {
            sb.append('[');
            for (int i = 0; i < list.size(); i++) {
                writeCompact(sb, list.get(i));
                if (i < list.size() - 1) {
                    sb.append(", ");
                }
            }
            sb.append(']');
        } else {
            sb.append(scalar(value));
        }
    }

    private static void writeValue(StringBuilder sb, Object value, int indent) {
        if (value instanceof Map<?, ?> map) {
            writeObject(sb, map, indent);
        } else if (value instanceof List<?> list) {
            writeArray(sb, list, indent);
        } else {
            sb.append(scalar(value));
        }
    }

    private static void writeObject(StringBuilder sb, Map<?, ?> map, int indent) {
        if (map.isEmpty()) {
            sb.append("{}");
            return;
        }
        sb.append("{\n");
        int i = 0;
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            indent(sb, indent + 2);
            sb.append('"').append(escape(String.valueOf(entry.getKey()))).append("\": ");
            writeValue(sb, entry.getValue(), indent + 2);
            if (++i < map.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append('}');
    }

    private static void writeArray(StringBuilder sb, List<?> list, int indent) {
        if (list.isEmpty()) {
            sb.append("[]");
            return;
        }
        sb.append("[\n");
        for (int i = 0; i < list.size(); i++) {
            indent(sb, indent + 2);
            writeValue(sb, list.get(i), indent + 2);
            if (i + 1 < list.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        indent(sb, indent);
        sb.append(']');
    }

    private static String scalar(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Boolean || value instanceof Number) {
            return value.toString();
        }
        return '"' + escape(String.valueOf(value)) + '"';
    }

    private static void indent(StringBuilder sb, int spaces) {
        sb.append(" ".repeat(spaces));
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}
