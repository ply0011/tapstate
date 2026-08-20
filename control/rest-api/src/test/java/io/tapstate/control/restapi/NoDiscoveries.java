package io.tapstate.control.restapi;

import io.tapstate.spi.store.DiscoveredSourceModel;
import io.tapstate.spi.store.SchemaStore;
import java.util.Optional;

/**
 * A schema store nothing has been discovered in — the state every connection starts in. Suites that
 * stand the browse face up for some other reason use it so a listing carries no fields, which is what
 * a real store answers before any discovery has run.
 */
final class NoDiscoveries implements SchemaStore {

    @Override
    public void save(DiscoveredSourceModel discovered) {
        throw new UnsupportedOperationException("this store is only ever read");
    }

    @Override
    public Optional<DiscoveredSourceModel> get(String connectionId) {
        return Optional.empty();
    }
}
