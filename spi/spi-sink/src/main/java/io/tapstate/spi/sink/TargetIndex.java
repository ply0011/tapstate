package io.tapstate.spi.sink;

import java.io.Serializable;
import java.util.List;

/**
 * One index on the table a sink writes to: the fields it covers, in order, and whether it is unique.
 *
 * <p>An index travels with the target model rather than being applied out of band, so whoever creates
 * the table creates its indexes in the same act. A store that cannot create indexes ignores them; the
 * model states what the table should have, not how a particular store gets there.
 *
 * <p>Uniqueness is part of the index, not a property of the fields: the same columns can carry a
 * plain index in one table and a unique one in another, and only the second is a claim that they
 * identify a row.
 *
 * <p>Serializable so a resolved model travels with the sink factory the engine ships onto the DAG.
 */
public record TargetIndex(List<String> fields, boolean unique) implements Serializable {

    public TargetIndex {
        if (fields == null || fields.isEmpty()) {
            throw new IllegalArgumentException("an index must cover at least one field");
        }
        fields = List.copyOf(fields);
    }
}
