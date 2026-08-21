package io.tapstate.core.catalog;

import java.util.Collections;
import java.util.List;

import io.tapstate.core.model.SourceMode;

/**
 * One connector's fully resolved catalog row: identity, organising group, the source modes the
 * grammar may pair it with, table-discovery ability, sink capability, whether it can be a push
 * target, the connection config form and the provenance. This is what the wizard, validate and
 * explain read; the build tool generates it, the runtime loader reconstructs it.
 */
public record ConnectorCatalogEntry(
        String id,
        String name,
        String displayName,
        String icon,
        ConnectorGroup group,
        List<SourceMode> modes,
        Discovery discovery,
        SinkCapability sink,
        boolean pushOut,
        List<ConfigField> config,
        Provenance provenance) {

    public ConnectorCatalogEntry {
        modes = modes == null ? List.of() : Collections.unmodifiableList(List.copyOf(modes));
        config = config == null ? List.of() : Collections.unmodifiableList(List.copyOf(config));
    }

    /**
     * Whether these modes may be treated as the connector's full matrix, rather than a guess to defer
     * on. Capability derivation only yields {@code snapshot} / {@code cdc}; the unbounded
     * {@code stream} / {@code api} / {@code file} modes cannot be told apart from capabilities and so
     * must be declared. Derivation is therefore authoritative only for a database, whose real modes
     * are exactly the derivable ones; any other connector is trusted only once somebody declared its
     * modes — wherever that declaration was written.
     *
     * <p>Asks {@link ModeSource#isDeclaration()} rather than comparing against one constant: the
     * question is whether a person declared these modes, and more than one source carries such a
     * declaration. Naming a single constant here would quietly demote every entry from the others.
     */
    public boolean modesAreTrustworthy() {
        return group() == ConnectorGroup.DATABASE
                || provenance().modeSource().values().stream().anyMatch(ModeSource::isDeclaration);
    }
}
