#!/usr/bin/env bash
#
# Regenerates the bundled connector catalog from a connectors checkout, in one command.
#
# The refresh is four Maven runs across three modules, each gated on its own system properties:
#
#   1. catalog-assembler walks the checkout and writes the probe manifest (the worklist of Java
#      connectors to classload);
#   2. catalog-derive reads that manifest, classloads each connector's built jar and writes the
#      capability bitmap;
#   2b. adapter-pdk reads the same three artifacts back and reconciles the bitmap against the other
#      implementation of the same derivation - the live one - and against the catalog row; skipped
#      by a spec-only refresh, which builds no jars to reconcile;
#   3. catalog-assembler merges spec, overlay and bitmap and rewrites the checked-in catalog and
#      ingest report.
#
# Driving that by hand is three long command lines whose property names live in three different
# files, and getting one wrong does not fail. Every one of these runs is a JUnit test that *skips*
# when its inputs are absent - an absent checkout, an absent bitmap, a property spelled wrong - and a
# skipped test is not a failure: surefire records it as skipped and Maven exits 0. So a refresh can
# regenerate nothing, print no error, and exit 0. That is what this script exists to prevent: after
# each step it reads the surefire report for that step's own class and refuses unless the test
# actually executed, and then checks the output the step was supposed to produce.
#
# Two ways to run it, because the two upstream drifts cost two orders of magnitude apart:
#
#   scripts/refresh-catalog.sh --connectors ../tapdata-connectors
#       A full refresh. Builds the connector jars from the checkout, derives the bitmap, regenerates.
#       Needed when a connector's capabilities changed, or a connector was added or removed.
#
#   scripts/refresh-catalog.sh --connectors ../tapdata-connectors --spec-only
#       A spec-face refresh, no jars built. Reuses the checked-in bitmap, so it takes seconds. Enough
#       when only specification files changed - field names, labels, defaults.
#
# Options:
#   --connectors <path>   the tapdata-connectors checkout to read (required)
#   --dist <dir>          a directory of already-built connector jars; skips the build step
#   --spec-only           reuse the checked-in bitmap instead of building jars and deriving
#   --bitmap <path>       the bitmap to reuse (default: the checked-in one)
#   --sha <sha>           the connectors revision to stamp into provenance, when the checkout is not
#                         a git working tree (a tarball, a vendored copy)
#   --keep-workspace      leave the scratch directory in place for inspection
#
# It writes to the working tree: core/core-catalog/src/main/resources/catalog/ and the ingest report.
# Review the diff it prints before committing.
set -uo pipefail

readonly ASSEMBLER_MODULE="tools/catalog-assembler"
readonly ASSEMBLER_REPORT="$ASSEMBLER_MODULE/target/surefire-reports/TEST-io.tapstate.tools.catalog.assembler.CatalogArtifactTest.xml"
readonly DERIVE_REPORT="tools/catalog-derive/target/surefire-reports/TEST-io.tapstate.tools.catalog.derive.CatalogDeriveRealRunTest.xml"
readonly HARNESS_REPORT="adapters/adapter-pdk/target/surefire-reports/TEST-io.tapstate.adapters.pdk.CapabilityHarnessRealJarTest.xml"
readonly CATALOG_DIR="core/core-catalog/src/main/resources/catalog"
readonly INGEST_REPORT="$ASSEMBLER_MODULE/ingest-report.md"
# Where the derived bitmap is checked in, so a spec-only refresh has a capability face to merge
# without building a single jar.
readonly CHECKED_IN_BITMAP="$ASSEMBLER_MODULE/capability-bitmap.tsv"

# The header block above, up to the first line that is not a comment - a fixed line range drifts the
# first time anyone adds a paragraph, and prints shell at the reader without failing anything.
usage() {
  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"
  echo
  echo "usage: refresh-catalog.sh --connectors <path> [--dist <dir>] [--spec-only] [--bitmap <path>]"
  echo "                          [--sha <sha>] [--keep-workspace]"
}

# Refusals before any work: exit 2, so a caller can tell "you asked for something impossible" from
# "the refresh ran and did not complete" (exit 1).
refuse() { printf 'refresh-catalog: %s\n' "$1" >&2; exit 2; }
fail()   { printf 'refresh-catalog: %s\n' "$1" >&2; exit 1; }

connectors=""; dist=""; bitmap=""; sha=""; spec_only=no; keep_workspace=no
while [ "$#" -gt 0 ]; do
  case "$1" in
    --connectors) connectors="${2:-}"; shift 2 ;;
    --dist)       dist="${2:-}"; shift 2 ;;
    --bitmap)     bitmap="${2:-}"; shift 2 ;;
    --sha)        sha="${2:-}"; shift 2 ;;
    --spec-only)  spec_only=yes; shift ;;
    --keep-workspace) keep_workspace=yes; shift ;;
    -h|--help)    usage; exit 0 ;;
    *)            usage >&2; refuse "unrecognised argument: $1" ;;
  esac
done

if [ -z "$connectors" ]; then
  usage >&2
  refuse "no connectors checkout named"
fi

# The repository this script lives in, so the run does not depend on the working directory.
repo_root="$(cd "$(dirname "$0")/.." && pwd)"
cd "$repo_root" || refuse "cannot enter $repo_root"

[ -d "$connectors" ] || refuse "no such connectors checkout: $connectors"
connectors="$(cd "$connectors" && pwd)"
# The assembler's walk reads connectors/ and connectors-javascript/ out of the checkout root. Pointed
# one level off - at the connectors/ directory itself, a common slip - the walk finds nothing and
# every downstream step is a green run over an empty worklist.
[ -d "$connectors/connectors" ] || refuse "$connectors has no connectors/ directory - is it the checkout root?"

if [ -z "$sha" ]; then
  sha="$(git -C "$connectors" rev-parse --short HEAD 2>/dev/null)"
  # Provenance is stamped into all 77 entries, so an unknown revision poisons the durable catalog.
  # The assembler refuses it too, but several minutes later, after the jars are built.
  [ -n "$sha" ] || refuse "cannot read a revision from $connectors - pass --sha <sha> if it is not a git checkout"
fi

workspace="$(mktemp -d)"
if [ "$keep_workspace" = yes ]; then
  echo "Scratch directory: $workspace"
else
  trap 'rm -rf "$workspace"' EXIT
fi

# Reads one attribute off the surefire report's <testsuite> element. Written on one line by surefire,
# but flattened first so a wrapped element still parses.
suite_attr() {
  tr '\n' ' ' < "$1" | sed -n "s/.*<testsuite[^>]*[[:space:]]$2=\"\([^\"]*\)\".*/\1/p" | head -1
}

# Runs one step and refuses unless it actually executed.
#
# Three different absences, three different messages, because they send you to three different
# places: a build that failed, a test that was there and skipped, and a test that was never selected
# at all. The report is deleted first - a report left by an earlier refresh says that run executed,
# and is indistinguishable from this run's own evidence.
run_step() {
  local label="$1" report="$2" log="$3"; shift 3
  rm -f "$repo_root/$report"
  if ! mvn "$@" > "$log" 2>&1; then
    tail -40 "$log" >&2
    fail "$label failed - the Maven run above did not succeed (full log: $log)"
  fi
  if [ ! -f "$repo_root/$report" ]; then
    fail "$label did not run - Maven exited 0 but wrote no surefire report, so the test was never selected (full log: $log)"
  fi
  local tests skipped
  tests="$(suite_attr "$repo_root/$report" tests)"
  skipped="$(suite_attr "$repo_root/$report" skipped)"
  if [ "${tests:-0}" -lt 1 ] || [ "${skipped:-0}" -gt 0 ]; then
    grep -iE 'skipp|assum' "$log" | tail -5 >&2
    fail "$label did not run - surefire reported ${tests:-0} test(s), ${skipped:-0} skipped (full log: $log)"
  fi
  echo "  $label ok"
}

echo "Refreshing the connector catalog from $connectors (revision $sha)"

# --- step 1: the probe manifest -------------------------------------------------------------------

manifest="$workspace/manifest.tsv"
# -am, not -pl alone: the merge rules this catalog is generated by live in core-catalog, which is a
# dependency of this module rather than part of it. Selected alone, the run resolves core-catalog from
# the local repository - whatever was last installed there - so a change to the merge rules produces a
# catalog generated by the previous ones, with nothing to say so. The -Dtest filter keeps the modules
# -am pulls in from running their own tests.
run_step "step 1 (probe manifest)" "$ASSEMBLER_REPORT" "$workspace/step1.log" \
  -B -pl "$ASSEMBLER_MODULE" -am test \
  -Dtest=CatalogArtifactTest#emitsTheProbeManifestWhenAskedTo \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtapstate.catalog.connectors="$connectors" \
  -Dtapstate.catalog.manifest="$manifest"
[ -s "$manifest" ] || fail "step 1 (probe manifest) produced no manifest at $manifest"
echo "  $(wc -l < "$manifest" | tr -d ' ') connectors to probe"

# --- step 2: the capability bitmap ----------------------------------------------------------------

skipped_list=""
if [ "$spec_only" = yes ]; then
  bitmap="${bitmap:-$repo_root/$CHECKED_IN_BITMAP}"
  [ -s "$bitmap" ] || refuse "spec-only needs a checked-in bitmap and $CHECKED_IN_BITMAP is absent or empty - run a full refresh once, or pass --bitmap"
  echo "  step 2 (capability bitmap) reusing $bitmap, skipping the connector build"
else
  if [ -z "$dist" ]; then
    dist="$workspace/dist"
    # The worklist decides what gets built: every module the manifest names, resolved to its path in
    # the checkout. Building a fixed list instead would silently derive nothing for any connector
    # added upstream since the list was written - and a connector with no jar is not an error, it is
    # a line in the report's "not derived" section, which is where such an omission would go to hide.
    modules=""
    while IFS=$'\t' read -r id module _; do
      [ -n "${module:-}" ] || continue
      for container in connectors connectors-javascript; do
        if [ -d "$connectors/$container/$module" ]; then
          modules="${modules:+$modules,}$id=$container/$module"
          break
        fi
      done
    done < "$manifest"
    [ -n "$modules" ] || fail "step 2 (capability bitmap) resolved no module paths out of $manifest"
    builder="${TAPSTATE_CONNECTOR_BUILD:-$repo_root/scripts/build-real-connectors.sh}"
    module_count="$(printf '%s\n' "$modules" | tr ',' '\n' | wc -l | tr -d ' ')"
    echo "  building $module_count connector module(s) from the manifest into $dist"
    if ! "$builder" --modules "$modules" --checkout "$connectors" "$dist" > "$workspace/build.log" 2>&1; then
      tail -40 "$workspace/build.log" >&2
      fail "step 2 (capability bitmap) could not build the connector jars (full log: $workspace/build.log)"
    fi
  fi
  [ -d "$dist" ] || refuse "no such dist directory: $dist"
  bitmap="$workspace/bitmap.tsv"
  run_step "step 2 (capability bitmap)" "$DERIVE_REPORT" "$workspace/step2.log" \
    -B -f tools/catalog-derive/pom.xml test \
    -Dtest=CatalogDeriveRealRunTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtapstate.derive.manifest="$manifest" \
    -Dtapstate.derive.dist="$dist" \
    -Dtapstate.derive.out="$bitmap"
  [ -s "$bitmap" ] || fail "step 2 (capability bitmap) produced no bitmap at $bitmap"
  skipped_list="$bitmap.skipped"
  # Written even when empty, so "nothing was skipped" and "the skip list was never produced" are not
  # the same answer. Without this the run below reports every connector as derived on the strength of
  # a file that was never written.
  [ -f "$skipped_list" ] || fail "step 2 (capability bitmap) wrote no skip list at $skipped_list"
  # Every connector on the worklist has to come back either derived or skipped-with-a-reason. Derive
  # is written that way, and says so, but nothing checked it: a connector that fell out between the
  # manifest and the bitmap is a connector whose modes silently empty in the regenerated catalog, and
  # it leaves no trace anywhere - not in the bitmap, which never mentions it, and not in the skip
  # list, which is what "we know why this one is missing" looks like.
  derived_count="$(grep -c . "$bitmap" | tr -d " ")"
  skipped_count="$(grep -c . "$skipped_list" | tr -d " ")"
  manifest_count="$(grep -c . "$manifest" | tr -d " ")"
  accounted=$((derived_count + skipped_count))
  if [ "$accounted" -ne "$manifest_count" ]; then
    fail "step 2 (capability bitmap) accounted for $accounted of the $manifest_count connectors on the worklist - $((manifest_count - accounted)) came back neither derived nor skipped"
  fi
  # How much of the catalog this refresh has a capability face for. A dist holding a handful of jars
  # regenerates a complete-looking catalog with the modes of everything else emptied out, and the only
  # things that say so are this count and the list at the end - the diff shows what changed, which for
  # a gutted entry looks like any other edit.
  echo "  derived $derived_count of $manifest_count connectors; $skipped_count produced no capability bits"

  # --- step 2b: the two derivations agree ---------------------------------------------------------
  #
  # The capabilities just derived are read a second time, by the other implementation - the one the
  # server runs when a connector is registered - and the two answers are reconciled against each other
  # and against the catalog row. They share no code and classload differently, and nothing downstream
  # would notice them drifting apart: the offline answer is what gets committed here, the live one is
  # what a registered connector actually gets, and a connector whose two answers differ is one whose
  # bundled row describes a connector the server does not see.
  #
  # Here rather than in a lane of its own because here is the only place all three inputs exist at
  # once - the worklist, the jars, and the bitmap just derived from them. Running it later against a
  # bitmap from a different build compares answers read out of jars that are gone.
  #
  # It refuses a dist too small to be a cross-check, which is also a refusal a deliberately partial
  # --dist run will meet. That is the same incomplete-dist run that regenerates a complete-looking
  # catalog with everything else emptied out, so it is not a shape worth passing quietly.
  run_step "step 2b (derivation agreement)" "$HARNESS_REPORT" "$workspace/step2b.log" \
    -B -pl adapters/adapter-pdk -am test \
    -Dtest=CapabilityHarnessRealJarTest \
    -Dsurefire.failIfNoSpecifiedTests=false \
    -Dtapstate.pdk.it.manifest="$manifest" \
    -Dtapstate.pdk.it.dist="$dist" \
    -Dtapstate.pdk.it.bitmap="$bitmap"
fi

# --- step 3: regenerate ---------------------------------------------------------------------------
#
# The toggle guard in the same class asserts this property is *off*, so it is red by construction on
# a regeneration run - selecting the single regenerating method leaves it out of the run rather than
# teaching anyone to read past a red build.
run_step "step 3 (regenerate)" "$ASSEMBLER_REPORT" "$workspace/step3.log" \
  -B -pl "$ASSEMBLER_MODULE" -am test \
  -Dtest=CatalogArtifactTest#generatedCatalogMatchesTheCheckedInArtifacts \
  -Dsurefire.failIfNoSpecifiedTests=false \
  -Dtapstate.catalog.connectors="$connectors" \
  -Dtapstate.catalog.update=true \
  -Dtapstate.catalog.bitmap="$bitmap" \
  -Dtapstate.catalog.sha="$sha"

# --- what changed ---------------------------------------------------------------------------------

echo
echo "Catalog diff ($CATALOG_DIR):"
git -C "$repo_root" --no-pager diff --stat -- "$CATALOG_DIR" | sed 's/^/  /'
git -C "$repo_root" diff --quiet -- "$CATALOG_DIR" && echo "  no change"
echo
echo "Ingest report diff ($INGEST_REPORT):"
git -C "$repo_root" --no-pager diff -- "$INGEST_REPORT" | sed -n '5,60p' | sed 's/^/  /'
git -C "$repo_root" diff --quiet -- "$INGEST_REPORT" && echo "  no change"

# The connectors that produced no capability bits, one line each with why. These are the entries whose
# modes can only come from the overlay, so they are the working list this refresh hands back - and
# they are invisible in the diff, which shows what changed rather than what never arrived.
echo
echo "Not derived this run:"
if [ -n "$skipped_list" ] && [ -s "$skipped_list" ]; then
  sed 's/\t/ - /' "$skipped_list" | sed 's/^/  /'
elif [ "$spec_only" = yes ]; then
  echo "  (spec-only run - the checked-in bitmap was reused, nothing was probed)"
else
  echo "  (none - every connector in the manifest classloaded and was probed)"
fi
