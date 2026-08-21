package io.tapstate.spi.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The field one term names, read into the steps it is made of.
 *
 * <p>A dot in a spelling is ambiguous and the ambiguity is not academic: documents nest, so a dot usually
 * steps into one, but a source is free to name a column {@code price.usd} and that name survives into the
 * stored document as a top-level key. Read as a step, a request for that column asks for a {@code usd}
 * inside a {@code price} — matching nothing, reporting nothing wrong, and looking exactly like a column
 * that happens to be empty. An escape is what lets the two be told apart: {@code \.} is part of a name,
 * a bare dot is a step.
 *
 * <p>Parsed here rather than where it is used, because it is used twice — once against rows already held
 * in memory, once translated into a backend's own dialect. Two parses of one spelling is two answers to
 * one question, and that failure shows up as a filter meaning different things in different views, with
 * nothing reporting the disagreement.
 *
 * <p>Refusals are bare rather than coded: this sits below the ring that owns user-facing codes, and the
 * surface that accepts a request is what turns a rejected spelling into one. Same split as the value
 * checks on a term's operator.
 */
public record FieldPath(List<String> segments, String asWritten) {

    private static final char ESCAPE = '\\';
    private static final char STEP = '.';

    public FieldPath {
        Objects.requireNonNull(asWritten, "asWritten");
        segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        if (segments.isEmpty()) {
            throw new IllegalArgumentException("a field path needs at least one segment");
        }
        segments.forEach(segment -> {
            if (segment.isEmpty()) {
                throw new IllegalArgumentException("field `" + asWritten + "` has a segment with no name");
            }
        });
    }

    /**
     * Reads a spelling into its steps. {@code \.} and {@code \\} stand for the characters they escape;
     * every other backslash is refused rather than guessed at.
     */
    public static FieldPath of(String spelling) {
        Objects.requireNonNull(spelling, "spelling");
        List<String> segments = new ArrayList<>();
        StringBuilder segment = new StringBuilder();
        for (int i = 0; i < spelling.length(); i++) {
            char current = spelling.charAt(i);
            if (current == ESCAPE) {
                if (i + 1 == spelling.length()) {
                    throw new IllegalArgumentException(
                            "field `" + spelling + "` ends part-way through an escape");
                }
                char escaped = spelling.charAt(++i);
                if (escaped != STEP && escaped != ESCAPE) {
                    // Neither keeping it nor dropping it is safe: both produce a field name the caller did
                    // not write, and the read that follows cannot report a name it was never given.
                    throw new IllegalArgumentException("field `" + spelling
                            + "` uses an escape that escapes nothing; only a dot and a backslash are escaped");
                }
                segment.append(escaped);
            } else if (current == STEP) {
                segments.add(segment.toString());
                segment.setLength(0);
            } else {
                segment.append(current);
            }
        }
        segments.add(segment.toString());
        return new FieldPath(segments, spelling);
    }

    /**
     * Whether every step is free of dots, so the spelling can travel to a backend that writes paths the
     * same way. One holding a literal dot cannot, and needs a translation that costs more — worth asking
     * once here rather than rediscovering at each call site.
     */
    public boolean isPlainPath() {
        return segments.stream().noneMatch(segment -> segment.indexOf(STEP) >= 0);
    }
}
