#!/usr/bin/env bash
# Cases for the catalog refresh driver, run against a scratch tree with a stub Maven on PATH.
#
# The refresh is three property-gated Maven runs, and every one of them is written to *skip* when its
# properties or inputs are absent: an absent connectors checkout, an absent bitmap, a property name
# typed wrong. A skipped JUnit test is not a failure - surefire reports it as skipped and Maven exits
# 0 - so the whole refresh can report success having regenerated nothing. That is the failure this
# script exists to make impossible, and every case below is one shape of it.
#
# The stub Maven is what lets those shapes be produced on demand: it writes exactly the outputs a real
# run of that step would, and each case suppresses one of them. No Java, no connectors, no network.
#
# Both halves of every answer are checked - the exit code and the reason given. A driver that refuses
# for the wrong reason is not refusing, it is coincidentally red.
#
# Run it from anywhere. Exits 0 if every case holds.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
driver="$here/refresh-catalog.sh"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
passed=0
failed=0

assembler_report="tools/catalog-assembler/target/surefire-reports/TEST-io.tapstate.tools.catalog.assembler.CatalogArtifactTest.xml"
derive_report="tools/catalog-derive/target/surefire-reports/TEST-io.tapstate.tools.catalog.derive.CatalogDeriveRealRunTest.xml"

# --- the stub Maven -----------------------------------------------------------------------------
#
# It reads the same properties the real runs are driven by and produces that step's observable output.
# The SMOKE_* variables below each remove one of those, which is how a case asks for the skip shape it
# is about. It is written once, here, so a case differs from the happy path by exactly one variable.
make_mvn_stub() {
  local shim="$scratch/shim"
  mkdir -p "$shim"
  cat > "$shim/mvn" <<'STUB'
#!/usr/bin/env bash
# Stands in for the three property-gated Maven runs of a refresh.
set -u
tree="$SMOKE_TREE"
echo "$*" >> "$tree/.mvn-args"
manifest=""; bitmap_out=""; bitmap_in=""; update=no
for arg in "$@"; do
  case "$arg" in
    -Dtapstate.catalog.manifest=*) manifest="${arg#*=}" ;;
    -Dtapstate.derive.out=*)       bitmap_out="${arg#*=}" ;;
    -Dtapstate.catalog.bitmap=*)   bitmap_in="${arg#*=}" ;;
    -Dtapstate.catalog.update*)    update=yes ;;
  esac
done
report() {  # $1 = report path relative to the tree, $2 = skipped count
  mkdir -p "$tree/$(dirname "$1")"
  printf '<?xml version="1.0" encoding="UTF-8"?>\n<testsuite tests="1" errors="0" skipped="%s" failures="0">\n</testsuite>\n' \
    "$2" > "$tree/$1"
}
if [ -n "$manifest" ]; then                       # step 1: emit the probe manifest
  [ "${SMOKE_STEP1_EXIT:-0}" = 0 ] || exit "$SMOKE_STEP1_EXIT"
  # Exit 0 having written nothing at all: the shape of a selector that matched no test.
  if [ "${SMOKE_STEP1_SILENT:-no}" = yes ]; then exit 0; fi
  if [ "${SMOKE_STEP1_SKIP:-no}" = yes ]; then report "$ASSEMBLER_REPORT" 1; exit 0; fi
  if [ "${SMOKE_STEP1_EMPTY:-no}" = yes ]; then : > "$manifest"; report "$ASSEMBLER_REPORT" 0; exit 0; fi
  if [ "${SMOKE_MANIFEST_UNKNOWN:-no}" = yes ]; then
    printf 'ghost\tghost-connector\tio.tapdata.connector.ghost.GhostConnector\n' > "$manifest"
    report "$ASSEMBLER_REPORT" 0
    exit 0
  fi
  {
    printf 'mysql\tmysql-connector\tio.tapdata.connector.mysql.MysqlConnector\n'
    [ "${SMOKE_MANIFEST_SHORT:-no}" = yes ] || printf 'hazelcast\thazelcast-connector\tio.tapdata.connector.hazelcast.HazelcastConnector\n'
    [ "${SMOKE_MANIFEST_EXTRA:-no}" = yes ] && printf 'ghost\tghost-connector\tio.tapdata.connector.ghost.GhostConnector\n'
  } > "$manifest"
  report "$ASSEMBLER_REPORT" 0
  exit 0
fi
if [ -n "$bitmap_out" ]; then                     # step 2: derive the capability bitmap
  if [ "${SMOKE_STEP2_SKIP:-no}" = yes ]; then report "$DERIVE_REPORT" 1; exit 0; fi
  printf 'mysql\tBATCH_READ\tSTREAM_READ\n' > "$bitmap_out"
  [ "${SMOKE_NO_SKIPLIST:-no}" = yes ] || printf 'hazelcast\tno dist jar\n' > "$bitmap_out.skipped"
  report "$DERIVE_REPORT" 0
  exit 0
fi
if [ "$update" = yes ]; then                      # step 3: regenerate the checked-in catalog
  echo "read bitmap from $bitmap_in" > "$tree/.stub-step3"
  if [ "${SMOKE_STEP3_SKIP:-no}" = yes ]; then report "$ASSEMBLER_REPORT" 1; exit 0; fi
  report "$ASSEMBLER_REPORT" 0
  exit 0
fi
echo "stub mvn: no step recognised in: $*" >&2
exit 64
STUB
  chmod +x "$shim/mvn"
  # The driver builds jars through the sibling script; stub that too, so no case needs a network.
  cat > "$shim/build-real-connectors-stub.sh" <<'BUILDSTUB'
#!/usr/bin/env bash
set -u
[ "${SMOKE_BUILD_EXIT:-0}" = 0 ] || exit "$SMOKE_BUILD_EXIT"
for arg in "$@"; do dest="$arg"; done
echo "stub connector build: $*" >> "$SMOKE_TREE/.stub-build"
mkdir -p "$dest"
: > "$dest/mysql-connector-v1.0.0.jar"
BUILDSTUB
  chmod +x "$shim/build-real-connectors-stub.sh"
  printf '%s' "$shim"
}

seed_git_repo() {
  git -C "$1" init -q >/dev/null 2>&1
  git -C "$1" add -A >/dev/null 2>&1
  git -C "$1" -c user.email=s@e -c user.name=s commit -qm base >/dev/null 2>&1
}

# A tree shaped like the repository the driver runs in, plus a connectors checkout it can be pointed
# at. Every case differs from it by exactly one thing.
fresh_tree() {
  rm -rf "${scratch:?}/tree" "${scratch:?}/connectors"
  mkdir -p "$scratch/tree/tools/catalog-assembler" "$scratch/tree/tools/catalog-derive" \
      "$scratch/tree/core/core-catalog/src/main/resources/catalog" "$scratch/tree/scripts"
  : > "$scratch/tree/tools/catalog-assembler/ingest-report.md"
  cp "$driver" "$scratch/tree/scripts/refresh-catalog.sh"
  seed_git_repo "$scratch/tree"
  mkdir -p "$scratch/connectors/connectors/mysql-connector/src/main/resources"
  printf '{"id":"mysql"}' > "$scratch/connectors/connectors/mysql-connector/src/main/resources/spec.json"
  seed_git_repo "$scratch/connectors"
}

# Runs the driver over whatever the tree now holds, and checks the exit code and the reason together.
# Leading NAME=VALUE arguments are the case's one difference; the rest are the driver's own flags.
expect() {
  local name="$1" want_code="$2" want_text="$3"; shift 3
  local -a env_pairs=()
  while [ "$#" -gt 0 ] && [[ "$1" == *=* && "$1" != -* ]]; do env_pairs+=("$1"); shift; done
  local shim out code
  shim="$(make_mvn_stub)"
  out="$(cd "$scratch/tree" && env PATH="$shim:$PATH" SMOKE_TREE="$scratch/tree" \
      ASSEMBLER_REPORT="$assembler_report" DERIVE_REPORT="$derive_report" \
      TAPSTATE_CONNECTOR_BUILD="$shim/build-real-connectors-stub.sh" \
      "${env_pairs[@]}" bash "$scratch/tree/scripts/refresh-catalog.sh" "$@" 2>&1)"
  code=$?
  if [ "$code" = "$want_code" ] && printf '%s' "$out" | grep -qF -- "$want_text"; then
    printf '  ok    %s\n' "$name"
    passed=$((passed + 1))
  else
    printf '  FAIL  %s: wanted exit %s containing "%s", got exit %s:\n' \
      "$name" "$want_code" "$want_text" "$code"
    printf '%s\n' "$out" | sed 's/^/        /'
    failed=$((failed + 1))
  fi
}

# --help prints the header block, and the block is delimited by where the comments stop rather than by
# a line number - a range drifts silently the first time anyone adds a paragraph, and what leaks out is
# shell, printed at whoever asked for help without anything going red.
help_prints_no_shell() {
  local name="$1"; shift
  local out; out="$("$@" --help 2>&1)"
  local leaked; leaked="$(printf '%s\n' "$out" | grep -cE '^(set -|readonly |[a-z_]+=|if |for )' )"
  if [ "$leaked" = 0 ] && printf '%s' "$out" | grep -q .; then
    printf '  ok    %s\n' "$name"
    passed=$((passed + 1))
  else
    printf '  FAIL  %s: %s line(s) of shell leaked into --help\n' "$name" "$leaked"
    failed=$((failed + 1))
  fi
}

echo "refresh-catalog cases:"

# --- what it refuses before running anything ----------------------------------------------------

fresh_tree
expect "no connectors checkout named" 2 "usage: refresh-catalog.sh"

fresh_tree
mkdir -p "$scratch/not-a-checkout"
expect "a path that is not a connectors checkout" 2 "no connectors/ directory" \
  --connectors "$scratch/not-a-checkout"

fresh_tree
rm -rf "$scratch/connectors/.git"
expect "a checkout whose sha cannot be read" 2 "--sha" --connectors "$scratch/connectors"

# --- the shapes a step has when it did not actually run -------------------------------------------
#
# Each of these is a green Maven run that regenerated nothing. Left alone they end in a refresh that
# reports success, which is the whole reason this driver exists.

fresh_tree
expect "step 1 skipped rather than run" 1 "step 1 (probe manifest) did not run" \
  SMOKE_STEP1_SKIP=yes --connectors "$scratch/connectors"

fresh_tree
expect "step 1 failing is not reported as a skip" 1 "step 1 (probe manifest) failed" \
  SMOKE_STEP1_EXIT=1 --connectors "$scratch/connectors"

fresh_tree
expect "step 2 skipped rather than run" 1 "step 2 (capability bitmap) did not run" \
  SMOKE_STEP2_SKIP=yes --connectors "$scratch/connectors"

fresh_tree
expect "step 3 skipped rather than run" 1 "step 3 (regenerate) did not run" \
  SMOKE_STEP3_SKIP=yes --connectors "$scratch/connectors"

# A report left behind by an earlier refresh says that run's step succeeded, and reads exactly like
# this run's evidence. The driver has to clear it before it looks.
fresh_tree
mkdir -p "$scratch/tree/$(dirname "$assembler_report")"
printf '<testsuite tests="1" errors="0" skipped="0" failures="0"></testsuite>\n' \
  > "$scratch/tree/$assembler_report"
expect "a previous run's report is not this run's evidence" 1 "wrote no surefire report" \
  SMOKE_STEP1_SILENT=yes --connectors "$scratch/connectors"

fresh_tree
expect "an empty manifest is not a manifest" 1 "produced no manifest" \
  SMOKE_STEP1_EMPTY=yes --connectors "$scratch/connectors"

fresh_tree
expect "a failing connector build stops the refresh" 1 "could not build the connector jars" \
  SMOKE_BUILD_EXIT=1 --connectors "$scratch/connectors"

# --- the spec-only run ----------------------------------------------------------------------------

# "Nothing was skipped" and "the skip list was never written" are the same empty answer, and the one
# that matters is the second: it reports every connector as derived when none of them were looked at.
fresh_tree
expect "an unwritten skip list is not an empty one" 1 "wrote no skip list" \
  SMOKE_NO_SKIPLIST=yes --connectors "$scratch/connectors"

fresh_tree
expect "spec-only without a checked-in bitmap" 2 "capability-bitmap.tsv" \
  --connectors "$scratch/connectors" --spec-only

fresh_tree
printf 'mysql\tBATCH_READ\n' > "$scratch/tree/tools/catalog-assembler/capability-bitmap.tsv"
expect "spec-only builds no jars" 0 "skipping the connector build" \
  --connectors "$scratch/connectors" --spec-only

# --- the full run ---------------------------------------------------------------------------------

fresh_tree
expect "a full refresh reports all three steps" 0 "step 3 (regenerate) ok" \
  --connectors "$scratch/connectors"

fresh_tree
expect "a full refresh names what it could not derive" 0 "hazelcast" \
  --connectors "$scratch/connectors"

# A dist holding a handful of jars regenerates a complete-looking catalog with everything else's modes
# emptied out. The count is pinned exactly, because a plausible wrong denominator - the manifest, say,
# rather than what derive actually accounted for - reads as a fuller refresh than happened.
fresh_tree
expect "a full refresh counts what it derived against the worklist" 0 \
  "derived 1 of 2 connectors; 1 produced no capability bits" --connectors "$scratch/connectors"

# A connector that comes back neither derived nor skipped is one whose modes quietly empty in the
# regenerated catalog, and it appears in neither file - the absence is the whole signal.
fresh_tree
expect "a connector that came back neither derived nor skipped" 1 "came back neither derived nor skipped" \
  SMOKE_MANIFEST_EXTRA=yes --connectors "$scratch/connectors"

fresh_tree
expect "a full refresh reports the catalog diff" 0 "Catalog diff" \
  --connectors "$scratch/connectors"

# What gets built comes from the manifest, not from a list written into a script. A connector added
# upstream has to reach the build by itself, or it lands in the report's "not derived" section - an
# omission that looks exactly like a connector that has no jar to build.
fresh_tree
expect "the modules built come from the manifest" 0 "1 connector module(s) from the manifest" \
  --connectors "$scratch/connectors"

fresh_tree
mkdir -p "$scratch/connectors/connectors/mongodb-connector/src/main/resources"
expect "a manifest module absent from the checkout is not silently dropped" 1 \
  "resolved no module paths" SMOKE_MANIFEST_UNKNOWN=yes --connectors "$scratch/connectors"

# The catalog is generated by merge rules that live in a dependency of the module being run, so a
# selection that does not build the reactor resolves them from whatever was last installed locally.
# The result is a complete catalog generated by the previous rules, and nothing anywhere says so -
# which is why this is pinned on the command rather than left to whoever reads the script.
fresh_tree
expect "the reactor is built, not resolved from the local repository" 0 "step 3 (regenerate) ok" \
  --connectors "$scratch/connectors"
without_am="$(grep -c -- '-pl tools/catalog-assembler test' "$scratch/tree/.mvn-args" 2>/dev/null || true)"
with_am="$(grep -c -- '-pl tools/catalog-assembler -am test' "$scratch/tree/.mvn-args" 2>/dev/null || true)"
if [ "$with_am" = 2 ] && [ "$without_am" = 0 ]; then
  printf '  ok    %s\n' "both assembler steps build the reactor"
  passed=$((passed + 1))
else
  printf '  FAIL  %s: wanted 2 runs with -am and 0 without, got %s with and %s without\n' \
    "both assembler steps build the reactor" "$with_am" "$without_am"
  failed=$((failed + 1))
fi

help_prints_no_shell "--help prints documentation, not source" bash "$driver"

echo
if [ "$failed" -gt 0 ]; then
  printf '%s passed, %s FAILED\n' "$passed" "$failed"
  exit 1
fi
printf '%s passed\n' "$passed"
