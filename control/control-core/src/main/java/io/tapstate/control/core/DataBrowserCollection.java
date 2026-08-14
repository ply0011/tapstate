package io.tapstate.control.core;

import java.util.List;
import java.util.Objects;

/**
 * One collection a source's database holds, with what is known about it beyond its name: the class of
 * collection it is, the fields something discovered on it, and the text whoever declared it wrote.
 *
 * <p>Only {@code name} is always answered. The other three are derived from sources that may not
 * exist for a given collection, and are null when they do not — the caller is told nothing rather
 * than told an empty or an invented thing. That distinction is the point of the type: an empty field
 * list states that the collection has no fields, which is a different claim from "nobody has
 * discovered this connection", and the surfaces carry the difference through by leaving the key out
 * rather than sending a value under it.
 *
 * <p>{@code kind} says what class of collection this is, and is present only for a collection some
 * view declares as where it materializes. The listing covers everything the database holds, and a
 * database holds far more than a workspace authored; answering "view" for all of them would state,
 * of a collection somebody made by hand, that a pipeline materializes it. Absent is the honest
 * answer there, and it is the same answer the other two give when nothing declared them.
 *
 * <p>{@code fields} names the collection's top-level fields, array fields included, in the order
 * discovery reported them. It is present only when the latest discovery on this source's connection
 * named this collection; a discovery that never ran, and one that ran without seeing this collection,
 * are the same state here — nothing was said about it.
 *
 * <p>{@code description} is the authored text of the declaring view. It is absent both for a
 * collection no view declares and for one whose view wrote nothing — which is why it is not the same
 * question as {@code kind}: a declared but undescribed collection still has a kind.
 */
public record DataBrowserCollection(String name, String kind, List<String> fields, String description) {

    /** A collection a workspace materializes into. The only class there is today. */
    public static final String VIEW = "view";

    public DataBrowserCollection {
        Objects.requireNonNull(name, "name");
        fields = fields == null ? null : List.copyOf(fields);
    }
}
