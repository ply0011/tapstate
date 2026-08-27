package io.tapstate.core.catalog;

import java.util.EnumMap;
import java.util.List;
import java.util.Set;

import io.tapstate.core.model.SourceMode;

/**
 * Resolves a connector's source modes from its derived capabilities and any declared override.
 *
 * <p>Two modes are derivable from registered capabilities: batch read implies {@code snapshot} and
 * stream read implies {@code cdc} (the database default). The other three modes ({@code stream},
 * {@code api}, {@code file}) cannot be told apart from capabilities, so a connector declares them.
 * A declaration is the connector's complete mode set and entirely replaces the derived defaults —
 * otherwise a SaaS connector would keep a wrongly-derived {@code cdc}.
 *
 * <p>Three sources, in order: this repository's overlay, then the connector's own upstream
 * declaration, then derivation. The overlay wins outright where it speaks, because it is the one
 * that was reviewed here; where it says nothing the other two are untouched. Agreement between the
 * first two is not interesting and is not reported anywhere — only a disagreement is.
 */
public final class ModeResolver {

    private ModeResolver() {
    }

    public static ModeResolution resolve(Set<DerivedCapability> capabilities, List<String> declaredModes,
                                        List<String> overlayModes) {
        EnumMap<SourceMode, ModeSource> modes = new EnumMap<>(SourceMode.class);
        if (overlayModes != null) {
            for (String overlay : overlayModes) {
                modes.put(parseMode(overlay), ModeSource.OVERLAY);
            }
            return new ModeResolution(modes);
        }
        if (declaredModes != null) {
            for (String declared : declaredModes) {
                modes.put(parseMode(declared), ModeSource.DECLARED);
            }
            return new ModeResolution(modes);
        }
        if (capabilities.contains(DerivedCapability.BATCH_READ)) {
            modes.put(SourceMode.SNAPSHOT, ModeSource.DERIVED);
        }
        if (capabilities.contains(DerivedCapability.STREAM_READ)) {
            modes.put(SourceMode.CDC, ModeSource.DERIVED);
        }
        return new ModeResolution(modes);
    }

    private static SourceMode parseMode(String yaml) {
        for (SourceMode mode : SourceMode.values()) {
            if (mode.yaml().equals(yaml)) {
                return mode;
            }
        }
        throw new IllegalArgumentException("unknown declared mode: " + yaml);
    }
}
