package io.tapstate.tools.catalog.assembler;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;

import io.tapstate.core.catalog.CatalogEntryAssembler;
import io.tapstate.core.catalog.ConnectorCatalogEntry;
import io.tapstate.core.catalog.ConnectorOverlay;
import io.tapstate.core.catalog.ModeSource;
import io.tapstate.core.catalog.NormalizedSpec;
import io.tapstate.core.catalog.TapstateCatalog;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reconciles the checked-in capability bitmap against the checked-in catalog snapshot: every entry
 * is re-merged through the real merge rules and must come out byte-identical in the three fields the
 * bitmap alone decides. Bitmap, snapshot and overlay are all tracked files, so this needs no
 * connectors checkout and no PDK, and runs in every build.
 *
 * <p>The sibling reconciliation over in core-catalog covers the declaration side - the entries whose
 * modes this repository wrote down. That is 26 of the 78 rows. The other 52 rest entirely on
 * derivation, and until this class existed nothing in a normal build looked at them: the byte-lock
 * that would notice needs a connectors checkout, so it is skipped on every pull request. Hand-editing
 * a derived entry's modes was a silent, green change.
 *
 * <p>The two sides are read from different places on purpose: the snapshot off the classpath, which
 * is the copy that ships, and the bitmap off the working tree, which is the copy a person edits. A
 * reactor build makes them the same bytes. A build that resolved core-catalog from a stale local
 * repository instead would compare two revisions - the same trap as any {@code -pl} run without
 * {@code -am}, and not one this class can tell apart from real drift.
 *
 * <p><strong>What this cannot see.</strong> Two fields of an entry come from normalising the upstream
 * spec, which needs the connectors checkout this class deliberately does without: {@code config} and
 * {@code sink.writeSemantics}. They stay covered by the byte-lock alone. Naming the gap is the point -
 * a reconciliation that quietly compared two of five fields would read as "the snapshot is verified".
 */
class CatalogDerivationReconciliationTest {

    private final TapstateCatalog catalog = TapstateCatalog.load();
    private final ConnectorOverlay overlay = ConnectorOverlay.load();
    private final Map<String, Set<String>> bitmap = checkedInBitmap();

    @Test
    void everyEntryIsWhatTheCheckedInBitmapMergesTo() {
        List<String> drift = new ArrayList<>();
        int derivedEntriesCompared = 0;
        for (ConnectorCatalogEntry entry : catalog.all()) {
            // A connector with no bitmap row registered nothing this repository could derive from -
            // either it built and registered none of the three, or it could not be built at all. Both
            // mean the same thing to the merge, and both are already visible elsewhere: the ingest
            // report lists them, and the mode-signal baseline pins how many there are.
            Set<String> capabilities = bitmap.getOrDefault(entry.id(), Set.of());
            ConnectorCatalogEntry merged = CatalogEntryAssembler.assemble(
                    specOf(entry), capabilities, overlay, null, null);

            if (!merged.modes().equals(entry.modes())) {
                drift.add(entry.id() + ".modes: the bitmap merges to " + merged.modes()
                        + " but the snapshot carries " + entry.modes());
            }
            if (!merged.provenance().modeSource().equals(entry.provenance().modeSource())) {
                drift.add(entry.id() + ".modeSource: the bitmap merges to "
                        + new TreeMap<>(merged.provenance().modeSource())
                        + " but the snapshot carries " + new TreeMap<>(entry.provenance().modeSource()));
            }
            if (merged.sink().capable() != entry.sink().capable()) {
                drift.add(entry.id() + ".sink.capable: the bitmap merges to " + merged.sink().capable()
                        + " but the snapshot carries " + entry.sink().capable());
            }
            if (entry.provenance().modeSource().containsValue(ModeSource.DERIVED)) {
                derivedEntriesCompared++;
            }
        }

        assertThat(drift)
                .as("the shipped catalog no longer says what the checked-in bitmap merges to - the "
                        + "snapshot was edited, badly merged, or regenerated against a different bitmap")
                .isEmpty();
        assertThat(derivedEntriesCompared)
                .as("every row this run compared was decided by the overlay, so the derived side - the "
                        + "reason this class exists - went unchecked while the assertion above passed")
                .isPositive();
    }

    @Test
    void noEntryRestsOnAnUpstreamDeclarationThisGateCannotReproduce() {
        // The third mode source is the connector's own tapstate.modes block, and reading it needs the
        // connectors checkout this class does without. Today none is reachable: the overlay outranks
        // an upstream declaration wherever both speak, and every connector that declares upstream was
        // seeded into the overlay, so no entry is left attributing a mode to the upstream spec.
        //
        // If one appears, the reconciliation above would compare that entry against a merge run with
        // no declaration at all and report drift that is not drift. Failing here instead says which
        // entry it is and forces the choice: seed it into the overlay, or teach the loop to skip it.
        List<String> unreproducible = new ArrayList<>();
        for (ConnectorCatalogEntry entry : catalog.all()) {
            if (entry.provenance().modeSource().containsValue(ModeSource.DECLARED)) {
                unreproducible.add(entry.id() + ": " + new TreeMap<>(entry.provenance().modeSource()));
            }
        }
        assertThat(unreproducible)
                .as("an entry takes its modes from an upstream declaration, which this reconciliation "
                        + "cannot read without a connectors checkout")
                .isEmpty();
        assertThat(catalog.ids())
                .as("an empty catalog would make every check in this class vacuous")
                .isNotEmpty();
    }

    /**
     * The structural half of a spec, as far as the three reconciled fields need it. Rebuilt from the
     * entry rather than read from upstream, and that is not circular: none of the three fields takes
     * any value from here. Modes and their sources come from the bitmap, the overlay and the
     * declaration slot (held empty, see above); sink capability from the bitmap's {@code write_record}
     * and the overlay. Identity, group and config ride along only so the merge has a spec to run on -
     * they decide the fields this class does not compare.
     */
    private static NormalizedSpec specOf(ConnectorCatalogEntry entry) {
        return new NormalizedSpec(entry.id(), entry.name(), entry.displayName(), entry.icon(),
                entry.group(), List.of(), null, false, null);
    }

    /** Read through the same reader the assembler merges with, so the format has one definition. */
    private static Map<String, Set<String>> checkedInBitmap() {
        Path file = CatalogArtifactTest.bitmapFile();
        try {
            return BitmapReader.read(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the checked-in capability bitmap at " + file, e);
        }
    }
}
