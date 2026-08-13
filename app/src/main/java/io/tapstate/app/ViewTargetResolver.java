package io.tapstate.app;

import io.tapstate.core.model.Storage;
import io.tapstate.core.model.ViewBlock;
import io.tapstate.spi.sink.TargetIndex;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Resolves a declared view to the address its materialization writes to: which store, which
 * collection, and which indexes that collection carries.
 *
 * <p>Declaring a view is the whole instruction to materialize it, so every part of the address has a
 * default and none of it is the author's obligation. What the author may say is the physical name;
 * what they may not say is which store, because a deployment runs one managed state store and
 * scattering its name through the write path is how a rename becomes a sweep.
 *
 * <p>Only the warm layer resolves here. A view is one logical object that may later occupy several
 * physical layers, but they are not the same kind of thing: warm is a database collection, hot is an
 * in-memory keyspace, cold is a dataset and its partitioning. A collection name therefore describes
 * warm alone and must not be read as a shared physical name for the other two.
 */
final class ViewTargetResolver {

    /**
     * The source id of the managed state store every view materializes into. The one place the
     * deployment's name for that store is written down: the value travels into the target address
     * rather than being repeated at each use, so renaming the store is a single edit here.
     */
    static final String STATE_STORE_SOURCE_ID = "warehouse";

    private ViewTargetResolver() {
    }

    /** The resolved address of one view's materialization. */
    record ViewTarget(String sourceId, String collection, List<TargetIndex> indexes) {

        ViewTarget {
            Objects.requireNonNull(sourceId, "sourceId");
            Objects.requireNonNull(collection, "collection");
            indexes = indexes == null ? List.of() : List.copyOf(indexes);
        }
    }


    /**
     * The address the given view materializes to.
     *
     * <p>The key index is not an optimization. A reader paging through the collection needs a sort key
     * that is both indexed and unique: without the index the sort is held in memory and fails once the
     * data outgrows it - passing at every size a demo or a test reaches - and without uniqueness a
     * range start is ambiguous, so pages silently skip or repeat rows. The same index also turns a
     * duplicate key into a loud write failure rather than a silent second copy of a record the write
     * path believed it was updating.
     */
    static ViewTarget resolve(ViewBlock.Inline view) {
        Storage.Warm warm = view.storage() == null ? null : view.storage().warm();
        String collection = warm != null ? warm.collection() : view.id();

        List<TargetIndex> indexes = new ArrayList<>();
        if (view.primaryKey() != null && !view.primaryKey().isBlank()) {
            indexes.add(new TargetIndex(List.of(view.primaryKey()), true));
        }
        if (warm != null && warm.indexes() != null) {
            for (String field : warm.indexes()) {
                indexes.add(new TargetIndex(List.of(field), false));
            }
        }
        return new ViewTarget(STATE_STORE_SOURCE_ID, collection, indexes);
    }
}
