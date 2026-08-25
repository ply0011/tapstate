package io.tapstate.core.catalog;

/**
 * Where a connector's mode came from: a default derived from its registered capabilities, or an
 * explicit declaration. Recorded per mode so the catalog can be audited and the ingest report can
 * show which modes are heuristic.
 *
 * <p>Two of the three are declarations — one written by the connector's author in its own spec, one
 * written by this repository for the connectors it ships — and a caller asking "may I treat these
 * modes as the connector's full matrix" means the pair, never one constant. That question is
 * {@link #isDeclaration()}. Asking it by comparing against a single constant is how a second
 * declaration source silently stops counting: the comparison keeps compiling, keeps passing, and
 * quietly reclassifies every entry from the new source as an unreliable guess.
 */
public enum ModeSource {
    DERIVED("derived", false),
    DECLARED("declared", true),
    OVERLAY("overlay", true);

    private final String yaml;
    private final boolean declaration;

    ModeSource(String yaml, boolean declaration) {
        this.yaml = yaml;
        this.declaration = declaration;
    }

    public String yaml() {
        return yaml;
    }

    /**
     * Whether this source is a human declaration rather than a guess derived from the connector's
     * registered capabilities.
     *
     * <p>Carried as a property of each constant rather than computed from one, so that adding a
     * source forces whoever adds it to answer this question instead of inheriting whatever answer a
     * comparison elsewhere happened to imply.
     */
    public boolean isDeclaration() {
        return declaration;
    }
}
