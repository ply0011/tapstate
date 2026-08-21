#!/usr/bin/env bash
# Cases for the connector build, run against a scratch checkout with a stub Maven on PATH.
#
# Two lanes depend on this script and they want different things from it: the nightly witness lane
# wants the handful of connectors its scenarios drive, a catalog refresh wants every connector the
# catalog carries. That made the module list an input, and an input has a default - which is the one
# thing here that can regress without anybody noticing, because the lane that would notice runs at
# night and reports on connectors rather than on which connectors it built. So the default list is
# pinned by name and by order below.
#
# The rest of the cases are the staging rules the witnesses depend on: exactly one jar per connector,
# the shaded one and not the thin one. Both were already load-bearing before the list became an input;
# neither had a case.
#
# Both halves of every answer are checked - the exit code and the reason given.
#
# Run it from anywhere. Exits 0 if every case holds.
set -uo pipefail

here="$(cd "$(dirname "$0")" && pwd)"
builder="$here/build-real-connectors.sh"
scratch="$(mktemp -d)"
trap 'rm -rf "$scratch"' EXIT
passed=0
failed=0

# A stub Maven that stages whatever the real build would have produced for the modules it was asked
# for: one thin jar and one shaded jar per module, which is the pair the staging rule has to tell
# apart. It also records its own arguments, so a case can pin what was actually asked to be built.
make_shim() {
  local shim="$scratch/shim"
  rm -rf "$shim"; mkdir -p "$shim"
  cat > "$shim/mvn" <<'STUB'
#!/usr/bin/env bash
set -u
checkout=""; modules=""
next=""
for arg in "$@"; do
  case "$next" in f) checkout="$(dirname "$arg")" ;; pl) modules="$arg" ;; esac
  next=""
  case "$arg" in -f) next=f ;; -pl) next=pl ;; esac
done
echo "$modules" > "$SMOKE_MODULES_SEEN"
IFS=',' read -r -a mods <<< "$modules"
for module in "${mods[@]}"; do
  artifact="$(basename "$module")"
  mkdir -p "$checkout/$module/target"
  [ "${SMOKE_NO_JAR:-no}" = yes ] && continue
  : > "$checkout/$module/target/$artifact-1.0.0.jar"        # the thin jar, which must not be staged
  : > "$checkout/$module/target/$artifact-v1.0.0.jar"       # the shaded jar, which must
  if [ "${SMOKE_TWO_SHADED:-no}" = yes ]; then
    : > "$checkout/$module/target/$artifact-v1.0.1.jar"
  fi
done
exit 0
STUB
  chmod +x "$shim/mvn"
  # On Apple Silicon the script probes whether the pinned protoc publishes an arm64 build before it
  # builds anything. Answering yes here keeps every case off the network and takes the same branch a
  # Linux runner takes by skipping the block entirely, so both hosts run the same cases.
  printf '#!/usr/bin/env bash\nexit 0\n' > "$shim/curl"
  chmod +x "$shim/curl"
  printf '%s' "$shim"
}

# A checkout with the three default modules plus one extra, so a case can ask for something outside
# the default list and be seen to get it.
fresh_checkout() {
  rm -rf "${scratch:?}/checkout" "${scratch:?}/dest"
  for module in mysql-connector mongodb-connector postgres-connector redis-connector; do
    mkdir -p "$scratch/checkout/connectors/$module/src/main/resources"
  done
  mkdir -p "$scratch/checkout/connectors-common/debezium-bucket/debezium-bom"
  : > "$scratch/checkout/pom.xml"
  printf '<project><properties><version.com.google.protobuf>3.17.3</version.com.google.protobuf></properties></project>\n' \
      > "$scratch/checkout/connectors-common/debezium-bucket/debezium-bom/pom.xml"
}

expect() {
  local name="$1" want_code="$2" want_text="$3"; shift 3
  local -a env_pairs=()
  while [ "$#" -gt 0 ] && [[ "$1" == *=* && "$1" != -* && "$1" != /* ]]; do env_pairs+=("$1"); shift; done
  local shim out code
  shim="$(make_shim)"
  : > "$scratch/modules-seen"
  out="$(env PATH="$shim:$PATH" SMOKE_MODULES_SEEN="$scratch/modules-seen" \
      "${env_pairs[@]}" bash "$builder" "$@" 2>&1)"
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

# Pins what the build was actually asked to produce, which no message on stdout reports.
expect_modules() {
  local name="$1" want="$2"
  local seen; seen="$(cat "$scratch/modules-seen" 2>/dev/null)"
  if [ "$seen" = "$want" ]; then
    printf '  ok    %s\n' "$name"
    passed=$((passed + 1))
  else
    printf '  FAIL  %s: wanted modules "%s", got "%s"\n' "$name" "$want" "$seen"
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

echo "build-real-connectors cases:"

fresh_checkout
expect "no destination named" 1 "usage: build-real-connectors.sh"

fresh_checkout
expect "a module entry with no id" 2 "<id>=<module-path>" \
  --modules "connectors/mysql-connector" --checkout "$scratch/checkout" "$scratch/dest"

fresh_checkout
expect "a checkout that is not one" 2 "no connectors/ directory" \
  --modules "mysql=connectors/mysql-connector" --checkout "$scratch/checkout/connectors" "$scratch/dest"

# The default is what the nightly witness lane gets by passing no list at all. Pinned by name and by
# order: this is the one input whose regression the lane that uses it cannot report.
fresh_checkout
expect "the default list still builds the witness lane's connectors" 0 "Connector jars staged" \
  --checkout "$scratch/checkout" "$scratch/dest"
expect_modules "the default list, by name and in order" \
  "connectors/mysql-connector,connectors/mongodb-connector,connectors/postgres-connector"

fresh_checkout
expect "a named list is built instead of the default" 0 "Connector jars staged" \
  --modules "redis=connectors/redis-connector" --checkout "$scratch/checkout" "$scratch/dest"
expect_modules "only what was named" "connectors/redis-connector"

fresh_checkout
expect "an existing checkout is not cloned over" 0 "Building from the existing checkout" \
  --modules "mysql=connectors/mysql-connector" --checkout "$scratch/checkout" "$scratch/dest"

# The staging rules the witnesses and the catalog both resolve by.
fresh_checkout
expect "a module that built no jar" 1 "found 0" \
  SMOKE_NO_JAR=yes --modules "mysql=connectors/mysql-connector" --checkout "$scratch/checkout" "$scratch/dest"

fresh_checkout
expect "two shaded jars for one module" 1 "found 2" \
  SMOKE_TWO_SHADED=yes --modules "mysql=connectors/mysql-connector" --checkout "$scratch/checkout" "$scratch/dest"

# The thin jar shares the module's name and is not loadable as a connector. Staging both would make
# the destination ambiguous, which the witnesses refuse - so only the shaded one may land.
fresh_checkout
if out="$(env PATH="$(make_shim):$PATH" SMOKE_MODULES_SEEN="$scratch/modules-seen" \
    bash "$builder" --modules "mysql=connectors/mysql-connector" --checkout "$scratch/checkout" \
    "$scratch/dest" 2>&1)"; then
  staged="$(find "$scratch/dest" -name '*.jar' -type f | wc -l | tr -d ' ')"
  shaded="$(find "$scratch/dest" -name 'mysql-connector-v*.jar' -type f | wc -l | tr -d ' ')"
  if [ "$staged" = 1 ] && [ "$shaded" = 1 ]; then
    printf '  ok    %s\n' "only the shaded jar is staged"
    passed=$((passed + 1))
  else
    printf '  FAIL  %s: staged %s jars, %s of them shaded\n' "only the shaded jar is staged" "$staged" "$shaded"
    failed=$((failed + 1))
  fi
else
  printf '  FAIL  %s: the build refused:\n' "only the shaded jar is staged"
  printf '%s\n' "$out" | sed 's/^/        /'
  failed=$((failed + 1))
fi

help_prints_no_shell "--help prints documentation, not source" bash "$builder"

echo
if [ "$failed" -gt 0 ]; then
  printf '%s passed, %s FAILED\n' "$passed" "$failed"
  exit 1
fi
printf '%s passed\n' "$passed"
