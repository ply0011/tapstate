package io.tapstate.core.catalog;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

import io.tapstate.core.model.SourceMode;

/**
 * Where a catalog entry came from and how trustworthy each mode is. The spec path and content hash
 * are stamped per entry by the build tool; {@code pdkApiVersion}/{@code requiredLevel} are reserved
 * slots (null on the bundled path, filled by server register). {@code modeSource} records per mode
 * whether it was derived from capabilities or declared, so the ingest report can audit it.
 *
 * <p>The two revisions are a property of a whole catalog rather than of an entry, so they are stored
 * once in the index head and backfilled here when it is read. Stored per entry they were one value
 * repeated once per connector, and a refresh that changed a single connector rewrote every file —
 * which matters because the spec-face refresh opens a pull request daily, and one that touches every
 * file every day is one nobody reads.
 *
 * <p>Two of them rather than one because the two faces are refreshed by different jobs at different
 * rates: {@code specSha} is the revision the specification files were read at, {@code capabilitySha}
 * the revision the capability bitmap was derived at. A single revision would be stamped afresh by
 * whichever job ran last while the other face still came from an older revision, which turns the only
 * provenance there is into a confident wrong answer. They are equal after a full refresh and differ
 * after a spec-only one.
 *
 * <p>Both are null on a row with no catalog head to come from — a connector registered with a running
 * server, which is assembled one row at a time.
 */
public record Provenance(
        String specSha,
        String capabilitySha,
        String specPath,
        String specContentHash,
        String pdkApiVersion,
        String requiredLevel,
        Map<SourceMode, ModeSource> modeSource) {

    public Provenance {
        EnumMap<SourceMode, ModeSource> copy = new EnumMap<>(SourceMode.class);
        if (modeSource != null) {
            copy.putAll(modeSource);
        }
        modeSource = Collections.unmodifiableMap(copy);
    }

    /** The same provenance with the two catalog-wide revisions filled in from the index head. */
    Provenance withShas(String specSha, String capabilitySha) {
        return new Provenance(specSha, capabilitySha, specPath, specContentHash,
                pdkApiVersion, requiredLevel, modeSource);
    }
}
