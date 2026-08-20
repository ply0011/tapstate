package io.tapstate.cli;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;

/**
 * How an embedded value is laid out for a reader, in the one place both live views take it from.
 *
 * <p>The rule is the same wherever it is asked for: <b>a value that fits stays on its line, and only
 * one that does not is opened up</b> -- a member per line, the rule applied again at each level, so a
 * value that happens to be deep does not take the screen for being deep. Flattening the whole tree is
 * what this replaces on one side and pretty-printing every level is what it replaces on the other;
 * both spend the reader's attention on structure they did not ask about.
 *
 * <p>What differs between the callers is only whether the layout may leave something out. The in-place
 * view redraws a fixed frame and has to fit it, so it elides: past a few members it keeps both ends and
 * says how many it dropped, and a scalar still too wide is cut in the middle. The appended view is a
 * stream with no frame to fit, so it elides nothing and cuts nothing -- what it shows is the row.
 *
 * <p>One rule with a flag rather than two renderings of it: the two faces show the same data, and a
 * reader who learns to read one has learned to read the other. Two implementations would also be two
 * things to keep agreeing, which is the failure this file exists downstream of.
 */
final class NestedLayout {

    /** How many members survive at each end when the middle is dropped. */
    private static final int KEEP_HEAD = 3;
    private static final int KEEP_TAIL = 2;

    private static final int INDENT = 2;

    private NestedLayout() {
    }

    /**
     * The lines one value occupies.
     *
     * @param width  how wide a line may be
     * @param elides whether the layout may leave members out and cut an over-wide scalar. False means
     *               the value arrives whole however long it is, which is the appended view's contract.
     */
    static List<String> lines(Object value, int width, boolean elides) {
        String flat = JsonOut.compact(value);
        if (flat.length() <= width) {
            return List.of(flat);
        }
        if (value instanceof List<?> list && !list.isEmpty()) {
            return expand(list.size(), i -> "", list::get, "[",
                    list.size() == 1 ? " (1 item)" : " (" + list.size() + " items)", width, elides);
        }
        if (value instanceof Map<?, ?> map && !map.isEmpty()) {
            List<? extends Map.Entry<?, ?>> entries = List.copyOf(map.entrySet());
            return expand(entries.size(),
                    i -> JsonOut.compact(String.valueOf(entries.get(i).getKey())) + ": ",
                    i -> entries.get(i).getValue(), "{",
                    entries.size() == 1 ? " (1 field)" : " (" + entries.size() + " fields)",
                    width, elides);
        }
        // Nothing left to open up. A scalar wider than its line is cut out of the middle where the
        // caller has a frame to fit, and left alone where it does not.
        return List.of(elides ? cut(flat, width) : flat);
    }

    /**
     * Cuts an over-long value out of the middle, keeping both ends. What distinguishes two long values
     * of the same kind is usually at the end -- two rendered ids share a long prefix and differ in the
     * last few characters -- so cutting the tail off is what makes them unreadable, while cutting the
     * middle leaves both the kind and the identity legible. Wrapping is not the alternative where this
     * is used: the caller counts the lines it wrote in order to redraw over them.
     */
    static String cut(String text, int width) {
        if (text.length() <= width) {
            return text;
        }
        if (width <= 1) {
            return "…";
        }
        int head = width / 2;
        int tail = width - 1 - head;
        return text.substring(0, head) + "…" + text.substring(text.length() - tail);
    }

    private static List<String> expand(int size, IntFunction<String> prefix, IntFunction<Object> member,
            String open, String count, int width, boolean elides) {
        List<String> lines = new ArrayList<>();
        lines.add(open);
        boolean drops = elides && size > KEEP_HEAD + KEEP_TAIL;
        int head = drops ? KEEP_HEAD : size;
        for (int i = 0; i < head; i++) {
            lines.addAll(memberLines(prefix.apply(i), member.apply(i), width, i < size - 1, elides));
        }
        if (drops) {
            lines.add(" ".repeat(INDENT) + "… " + (size - KEEP_HEAD - KEEP_TAIL) + " more …");
            for (int i = size - KEEP_TAIL; i < size; i++) {
                lines.addAll(memberLines(prefix.apply(i), member.apply(i), width, i < size - 1, elides));
            }
        }
        // The total is written only where something was left out. On a level shown whole the reader can
        // count it, and a count beside every closing brace is noise around the one place it informs.
        lines.add(drops ? open.replace('[', ']').replace('{', '}') + count
                : String.valueOf(open.replace('[', ']').replace('{', '}')));
        return lines;
    }

    private static List<String> memberLines(String prefix, Object value, int width, boolean more,
            boolean elides) {
        int inner = Math.max(width - INDENT, 1);
        List<String> laid = lines(value, Math.max(inner - prefix.length() - (more ? 1 : 0), 1), elides);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < laid.size(); i++) {
            out.add(" ".repeat(INDENT) + (i == 0 ? prefix : "") + laid.get(i)
                    + (i == laid.size() - 1 && more ? "," : ""));
        }
        return out;
    }
}
