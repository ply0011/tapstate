package io.tapstate.core.catalog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import io.tapstate.core.model.SourceMode;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reconciles the two checked-in artifacts that must agree about the modes this repository declares:
 * the overlay, which is where the declaration is written, and the generated catalog snapshot, which
 * is what ships. Both are in this module's resources, so this runs in every build - no connectors
 * checkout, no PDK.
 *
 * <p>That matters because the byte-lock over the snapshot only runs in the refresh job, where a
 * connectors checkout exists. Between refreshes the snapshot is an ordinary tracked file: editable by
 * hand, by a bad merge resolution, or by a regeneration that ran without the overlay on the
 * classpath. Nothing in a normal build compared it against the declaration it is supposed to carry.
 *
 * <p><strong>What "nothing" measured as, before this class existed.</strong> Editing kafka's snapshot
 * entry to a mode the overlay does not declare did turn a build red - in core-dsl, because the DSL
 * corpus happens to contain scenarios that use kafka. That is coverage by coincidence: it depends on
 * a fixture elsewhere naming the connector, it reports as a DSL scenario failure rather than as a
 * disagreement between two files, and for the connectors no fixture mentions it does not happen at
 * all. The same edit applied to a connector nothing references passed the entire build.
 *
 * <p>The checks below are deliberately two-directional. Reading the overlay and confirming the
 * snapshot agrees catches a declaration that was dropped or overwritten; walking every entry and
 * confirming nothing claims an overlay provenance the overlay does not back catches the opposite -
 * a snapshot asserting this repository declared something it never did. A one-directional check
 * waves the second through, and the second is the one that forges authority rather than losing it.
 */
class CatalogOverlayReconciliationTest {

    private final TapstateCatalog catalog = TapstateCatalog.load();
    private final ConnectorOverlay overlay = ConnectorOverlay.load();

    @Test
    void everyDeclarationLandsOnAnEntryThatExists() {
        // A declaration for an id the catalog does not carry is inert: nothing reads it, and nothing
        // says so. It is what a rename or a dropped connector leaves behind.
        List<String> dangling = new ArrayList<>();
        for (String id : overlay.ids()) {
            if (!catalog.ids().contains(id)) {
                dangling.add(id);
            }
        }
        assertThat(dangling)
                .as("the overlay declares modes for connectors the catalog does not carry - the "
                        + "declaration is inert and nothing else reports it")
                .isEmpty();
    }

    @Test
    void theSnapshotCarriesExactlyTheModesTheOverlayDeclares() {
        List<String> drift = new ArrayList<>();
        int modesCompared = 0;
        for (String id : overlay.ids()) {
            if (!catalog.ids().contains(id)) {
                continue;                     // reported by the dangling-declaration test above
            }
            List<String> declared = overlay.modesFor(id);
            Set<String> carried = overlaySourcedModesOf(id);
            modesCompared += declared.size();
            if (!carried.equals(new TreeSet<>(declared))) {
                drift.add(id + ": overlay declares " + new TreeSet<>(declared)
                        + " but the snapshot carries " + carried + " as overlay-sourced");
            }
        }
        assertThat(drift)
                .as("the shipped catalog no longer says what this repository declares - the snapshot "
                        + "was edited, badly merged, or regenerated without the overlay")
                .isEmpty();
        assertThat(modesCompared)
                .as("a reconciliation that compared no modes passes without checking anything; the "
                        + "exact expected id set is pinned separately, by the baseline test")
                .isPositive();
    }

    @Test
    void noEntryClaimsAnOverlayProvenanceTheOverlayDoesNotBack() {
        // Walks every entry, not the overlay's ids, and that is the point: this is the direction in
        // which an entry invents an authority for itself. An id absent from the overlay entirely
        // cannot be reached by iterating the overlay, so it would never be looked at.
        List<String> forged = new ArrayList<>();
        for (String id : catalog.ids()) {
            Set<String> claimed = overlaySourcedModesOf(id);
            if (claimed.isEmpty()) {
                continue;
            }
            List<String> declared = overlay.modesFor(id);
            Set<String> backed = declared == null ? Set.of() : new TreeSet<>(declared);
            for (String mode : claimed) {
                if (!backed.contains(mode)) {
                    forged.add(id + "." + mode + ": the entry says this repository declared it, but "
                            + "the overlay declares " + (declared == null ? "nothing for this id" : backed));
                }
            }
        }
        assertThat(forged)
                .as("an entry claims a mode came from this repository's own declaration while the "
                        + "overlay does not declare it - the provenance is asserting an authority "
                        + "that does not exist")
                .isEmpty();
    }

    @Test
    void noDeclarationIsEmptyAndEveryModeItNamesIsARealMode() {
        // Measured: an empty mode list never reaches the assertion below, because the reader's own
        // fail-fast throws out of load() first and every test in this class errors instead. The empty
        // branch is kept anyway, as the checked-in-content half of a guard whose other half is a
        // runtime check - if that fail-fast is ever relaxed, this is what still refuses the content.
        // Why it is fatal at all: zero modes make mode validation return early, so the connector's
        // checks switch off silently while every other gate stays green. "We declared it" and "we
        // declared it supports nothing" must not look alike.
        //
        // The spelling check is the same failure wearing different clothes: a mode named "streem" is
        // a declaration that declares nothing, and it reaches no enum on the way to being ignored.
        Set<String> known = new TreeSet<>();
        for (SourceMode mode : SourceMode.values()) {
            known.add(mode.yaml());
        }
        List<String> malformed = new ArrayList<>();
        for (String id : overlay.ids()) {
            List<String> declared = overlay.modesFor(id);
            if (declared == null || declared.isEmpty()) {
                malformed.add(id + ": declares no modes at all");
                continue;
            }
            for (String mode : declared) {
                if (!known.contains(mode)) {
                    malformed.add(id + ": declares '" + mode + "', which is not one of " + known);
                }
            }
        }
        assertThat(malformed)
                .as("a declaration that is empty, or that names a mode no reader recognises, "
                        + "declares nothing while looking like it declares something")
                .isEmpty();
        assertThat(overlay.ids())
                .as("an empty overlay would make every check in this class vacuous")
                .isNotEmpty();
    }

    /** The modes {@code id}'s snapshot entry attributes to this repository's own declaration. */
    private Set<String> overlaySourcedModesOf(String id) {
        Set<String> sourced = new TreeSet<>();
        for (Map.Entry<SourceMode, ModeSource> attribution
                : catalog.byId(id).provenance().modeSource().entrySet()) {
            if (attribution.getValue() == ModeSource.OVERLAY) {
                sourced.add(attribution.getKey().yaml());
            }
        }
        return sourced;
    }
}
