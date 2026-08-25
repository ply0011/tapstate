package io.tapstate.app;

import io.tapstate.core.model.Resource;
import io.tapstate.spi.store.ArtifactMutation;
import io.tapstate.spi.store.ArtifactStore;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * An in-memory {@link ArtifactStore} for the assembly-layer tests: a batch upsert keyed by top-level id,
 * with the single-artifact {@code save} inherited from the port. Enough for the data-plane tests to seed a
 * pipeline and its referenced sources without a store backend.
 */
final class InMemoryArtifactStore implements ArtifactStore {

    private final Map<String, Resource> byId = new LinkedHashMap<>();

    @Override
    public void saveAll(List<Resource> artifacts) {
        for (Resource artifact : artifacts) {
            byId.put(artifact.id(), artifact);
        }
    }

    /**
     * Inserts only when the id is free, mirroring the backing store's atomic create. The distinction from
     * {@code saveAll} is the whole point for a caller that must not overwrite what somebody else declared,
     * so a double that upserted here would let such a caller pass while overwriting in production.
     */
    @Override
    public ArtifactMutation create(Resource artifact) {
        if (byId.containsKey(artifact.id())) {
            return ArtifactMutation.ALREADY_EXISTS;
        }
        byId.put(artifact.id(), artifact);
        return ArtifactMutation.CREATED;
    }

    @Override
    public Optional<Resource> get(String id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<Resource> list() {
        return List.copyOf(byId.values());
    }
}
