package io.tapstate.control.core;

import java.util.List;
import java.util.Objects;

/**
 * One collection a source's database holds, with what is known about it beyond its name: the class of
 * collection it is, the fields something discovered on it, and the text whoever declared it wrote.
 *
 * <p>{@code name} and {@code kind} are always answered. {@code fields} and {@code description} are
 * derived from sources that may not exist for a given collection, and are null when they do not — the
 * caller is told nothing rather than told an empty thing. That distinction is the point of the type:
 * an empty field list states that the collection has no fields, which is a different claim from
 * "nobody has discovered this connection", and the surfaces carry the difference through by leaving
 * the key out rather than sending an empty value under it.
 *
 * <p>{@code fields} names the collection's top-level fields, array fields included, in the order
 * discovery reported them. It is present only when the latest discovery on this source's connection
 * named this collection; a discovery that never ran, and one that ran without seeing this collection,
 * are the same state here — nothing was said about it.
 *
 * <p>{@code description} is the authored text of the view that declares this collection as where it
 * materializes. Most collections in a database were never authored here, so most carry none.
 */
public record DataBrowserCollection(String name, String kind, List<String> fields, String description) {

    /** A collection a workspace materializes into. The only class there is today. */
    public static final String VIEW = "view";

    public DataBrowserCollection {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
        fields = fields == null ? null : List.copyOf(fields);
    }
}
