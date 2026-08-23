#!/usr/bin/env bash
#
# Builds the real connector jars this repository's real-connector witnesses drive, from the
# connectors' own source.
#
# Those jars are published nowhere a build can fetch them. This lane used to name a directory somebody
# had staged on the runner by hand, which is exactly why it could never be scheduled: a nightly cannot
# depend on a human having put files somewhere first. Building them here removes that arrangement - a
# run needs the upstream repository and the PDK repository to be reachable, and nothing else.
#
# A failure to clone or build stops the run rather than leaving the destination empty. The witnesses
# gate on jars resolving, and an empty destination reads to that gate as "no real-connector run was
# intended" - a green build that drove no connector at all, which is the outcome this lane exists to
# prevent.
#
# The connector source is taken from its default branch, not a pinned commit. Its PDK dependencies are
# snapshots, so pinning the commit would not buy a reproducible build anyway; following the branch
# means an upstream change that breaks the connectors surfaces on the next nightly rather than at the
# next release.
#
# The nightly lane runs this, and so can a developer who wants the real-connector witnesses locally:
#
#   scripts/build-real-connectors.sh /tmp/connectors
#   mvn -pl e2e -am verify -Dapi.version=1.44 -Dtapstate.e2e.connectors-dir=/tmp/connectors \
#     -Dit.test=PublishedExamplesIT
#
# A second argument names the directory to clone into, which has to be empty; it defaults to a fresh
# temporary directory.
#
# Two callers, two module lists. The witness lane wants the handful of connectors its scenarios drive;
# a catalog refresh wants every connector the catalog carries, and that list is not knowable here - it
# comes from the probe manifest the assembler writes, which is derived from the checkout rather than
# written down. So the list is an input, defaulting to the witness lane's connectors:
#
#   --modules <id>=<module-path>[,...]  what to build; the id is the file-name prefix the witnesses
#                                       and the catalog resolve by, so it cannot be chosen freely
#   --checkout <path>                   build from an existing checkout instead of cloning; a refresh
#                                       already has one, and cloning a second copy of a 469MB
#                                       repository to build the same commit is pure cost
#   TAPSTATE_CONNECTOR_JAVA_HOME        env var: the JDK to compile the connectors with. They pin a
#                                       Lombok that JDK 21 breaks, so this falls back to the ambient
#                                       JDK when that is old enough, and refuses rather than failing
#                                       deep in the reactor.

set -euo pipefail

readonly SOURCE_REPOSITORY="https://github.com/tapdata/tapdata-connectors.git"

# The witness lane's connectors, as the specifications name them, paired with the module that builds
# each one. This is the default rather than the only option - see --modules above.
readonly DEFAULT_MODULES="mysql=connectors/mysql-connector,mongodb=connectors/mongodb-connector,postgres=connectors/postgres-connector"

modules_arg=""
checkout=""
positional=()
while [ "$#" -gt 0 ]; do
    case "$1" in
        --modules)  modules_arg="${2:?--modules needs a value}"; shift 2 ;;
        --checkout) checkout="${2:?--checkout needs a value}"; shift 2 ;;
        -h|--help)  awk 'NR > 1 && /^#/ { sub(/^# ?/, ""); print; next } NR > 1 { exit }' "$0"; exit 0 ;;
        --*)        echo "unrecognised option: $1" >&2; exit 2 ;;
        *)          positional+=("$1"); shift ;;
    esac
done

destination="${positional[0]:?usage: build-real-connectors.sh [--modules <id>=<path>,...] [--checkout <path>] <destination-directory> [workspace]}"
workspace="${positional[1]:-$(mktemp -d)}"

# Split the list into the two parallel arrays the rest of the script works with. A malformed entry is
# refused here rather than becoming a module path Maven cannot resolve several minutes later.
CONNECTOR_IDS=()
CONNECTOR_MODULES=()
IFS=',' read -r -a requested <<< "${modules_arg:-$DEFAULT_MODULES}"
for entry in "${requested[@]}"; do
    case "$entry" in
        *=*) CONNECTOR_IDS+=("${entry%%=*}"); CONNECTOR_MODULES+=("${entry#*=}") ;;
        *)   echo "--modules entries are <id>=<module-path>, got: $entry" >&2; exit 2 ;;
    esac
done
if [ "${#CONNECTOR_IDS[@]}" -eq 0 ]; then
    echo "--modules named nothing to build" >&2
    exit 2
fi

# The JDK this build compiles with, which is not the one the rest of the repository builds under.
#
# Several connectors pin a Lombok old enough to reach into javac internals that JDK 21 moved -
# doris, starrocks and zoho-desk at 1.18.24, yashandb at 1.18.20, and Lombok only learned JDK 21 in
# 1.18.30. Compiling one of those under a newer JDK dies with "NoSuchFieldError: ... JCTree$JCImport
# ... qualid", which names javac rather than the dependency that is actually too old, and it lands
# 37 modules into a 93-module reactor - so the cost of finding out is a long build, and what it
# points at is the wrong thing. Every module here targets Java 8, so nothing in this build wants a
# newer JDK anyway; the repository around it does, and that is whose JAVA_HOME would otherwise be
# inherited.
#
# Two sources: named outright, or the ambient one when it is already old enough - which is what a
# developer following the tutorial has, and why this stays quiet for them. A workflow whose runner
# builds this repository under a newer JDK installs a 17 as well and names it; nothing is guessed
# from the environment, because a guess that misses falls through to the ambient JDK and turns a
# lane that used to work into a refusal.
readonly LOMBOK_CEILING=17

java_major() {
    [ -x "$1/bin/javac" ] || return 1
    "$1/bin/javac" -version 2>&1 | sed -n 's/^javac \([0-9][0-9]*\).*/\1/p' | head -1
}

connector_java_home="${TAPSTATE_CONNECTOR_JAVA_HOME:-}"
if [ -z "$connector_java_home" ]; then
    connector_java_home="${JAVA_HOME:-}"
    if [ -z "$connector_java_home" ]; then
        javac_on_path="$(command -v javac || true)"
        [ -n "$javac_on_path" ] || { echo "no JDK found: neither JAVA_HOME nor javac on PATH" >&2; exit 2; }
        connector_java_home="$(dirname "$(dirname "$javac_on_path")")"
    fi
fi

major="$(java_major "$connector_java_home" || true)"
if [ -z "$major" ]; then
    echo "cannot read a version out of the JDK at $connector_java_home" >&2
    exit 2
fi
if [ "$major" -gt "$LOMBOK_CEILING" ]; then
    echo "the connectors need a JDK $LOMBOK_CEILING or older to build; $connector_java_home is $major" >&2
    echo "their pinned Lombok predates JDK 21 and dies mid-reactor on a javac internal, not on a" >&2
    echo "message naming Lombok - so this refuses here instead. Point TAPSTATE_CONNECTOR_JAVA_HOME" >&2
    echo "at a JDK $LOMBOK_CEILING, or install one alongside the newer JDK on a runner." >&2
    exit 2
fi

mkdir -p "$destination"
destination="$(cd "$destination" && pwd)"

if [ -n "$checkout" ]; then
    [ -d "$checkout/connectors" ] || { echo "$checkout has no connectors/ directory" >&2; exit 2; }
    checkout="$(cd "$checkout" && pwd)"
    echo "Building from the existing checkout at $checkout"
else
    checkout="$workspace/tapdata-connectors"
    echo "Cloning the connector source into $workspace"
    git clone --depth 1 --quiet "$SOURCE_REPOSITORY" "$checkout"
fi

# The protoc this build compiles with, on a host the pinned protoc was never published for.
#
# One module in this set - the vendored PostgreSQL Debezium connector - compiles a .proto during the
# build, with a plugin that fetches a protoc binary for the host out of a Maven repository. The version
# it asks for is pinned by the connectors' own bom, and protoc published no arm64 macOS binary until
# 3.17, so on Apple Silicon that fetch is for an artifact that does not exist.
#
# Nothing reports that as the problem. A mirror answers the missing path with a redirect page, the
# plugin saves the HTML as the executable, and the build dies several steps later inside "protoc.exe"
# with a shell syntax error about "<head><title>301 Moved Permanently</title></head>" - no mention of
# an architecture anywhere. It reads like a broken network or a bad mirror, which is why this is worth
# handling here rather than leaving to whoever hits it: the message sends you somewhere else entirely.
#
# The fallback is the x86_64 build of the same version, which Rosetta runs and which emits sources
# identical to a native run. The same version rather than a newer one on purpose: that property is
# also the protobuf runtime the connectors compile against, so moving it moves both. And it is only
# reached when there really is no native build - a version that has one is left to the plugin, so this
# stops applying by itself once the connectors move past 3.17.
protoc_flags=()
if [ "$(uname -s)" = "Darwin" ] && [ "$(uname -m)" = "arm64" ]; then
    bom="$checkout/connectors-common/debezium-bucket/debezium-bom/pom.xml"
    protoc_version="$(sed -n 's|.*<version\.com\.google\.protobuf>\(.*\)</version\.com\.google\.protobuf>.*|\1|p' \
        "$bom" | head -1)"
    if [ -z "$protoc_version" ]; then
        echo "cannot read the pinned protoc version out of $bom" >&2
        exit 1
    fi
    protoc_base="https://repo1.maven.org/maven2/com/google/protobuf/protoc/$protoc_version"
    if curl -fsIL "$protoc_base/protoc-$protoc_version-osx-aarch_64.exe" >/dev/null 2>&1; then
        echo "protoc $protoc_version has an arm64 build; leaving the plugin to fetch it"
    else
        echo "protoc $protoc_version publishes no arm64 build; staging the x86_64 one to run under Rosetta"
        curl -fsSL -o "$workspace/protoc" "$protoc_base/protoc-$protoc_version-osx-x86_64.exe"
        chmod +x "$workspace/protoc"
        if ! "$workspace/protoc" --version >/dev/null 2>&1; then
            echo "the protoc staged at $workspace/protoc does not run on this host" >&2
            echo "on Apple Silicon that is Rosetta: softwareupdate --install-rosetta --agree-to-license" >&2
            exit 1
        fi
        protoc_flags=(-DprotocCommand="$workspace/protoc")
    fi
fi

modules="$(IFS=,; echo "${CONNECTOR_MODULES[*]}")"
echo "Building $modules with the JDK at $connector_java_home (Java $major)"
# No exec.skip here, and that is load-bearing rather than an omission: the postgres connector used to
# run an encryptor over its own shaded jar at package time, and the result was not a zip - no
# end-of-central-directory record - while this product opens a connector artifact with
# java.util.jar.JarFile to read its specification before anything else happens. That step is gone
# upstream, so the build produces the same readable shape every other connector already ships in.
# A checkout old enough to still carry it fails here in a way that points somewhere else entirely:
# the jar stages fine and the failure lands much later, at the first read of the artifact.
# The odd expansion keeps an empty array from tripping set -u on the bash a Mac ships.
#
# Cleaning first, which is not tidiness. Upstream stamps a build timestamp into each shaded jar's
# name, so a second run in the same checkout leaves the first run's jars beside its own - and the
# staging rule below requires exactly one, so the run ends by refusing over a jar it produced itself.
# The refusal is the mild half: a checkout built at one upstream revision and then again at another
# holds two different connectors under two names, and provenance is stamped per revision, so the one
# that gets staged decides what the catalog says. A runner clones fresh and never sees either; the
# developer running a refresh locally sees both.
JAVA_HOME="$connector_java_home" \
    mvn -B -f "$checkout/pom.xml" -pl "$modules" -am -DskipTests \
    ${protoc_flags[@]+"${protoc_flags[@]}"} clean package

# Stage one jar per connector, and insist on exactly one.
#
# Each module builds two jars: a thin one holding just its own classes, and the shaded runtime jar that
# carries its dependencies. Only the shaded one is loadable as a connector, and the two are told apart
# by the "-v" the shade plugin puts before the version. Matching on the connector id alone would take
# both, and the witnesses refuse an ambiguous match - so the selector has to be this specific.
#
# Staging the whole build output would break them the same way, on the sibling connectors that share a
# prefix: mysql-pxc, mongodb-atlas.
for index in "${!CONNECTOR_IDS[@]}"; do
    id="${CONNECTOR_IDS[$index]}"
    module="${CONNECTOR_MODULES[$index]}"
    artifact="$(basename "$module")"
    built=()
    while IFS= read -r jar; do built+=("$jar"); done < <(
        find "$checkout/$module/target" -maxdepth 1 -name "$artifact-v*.jar" -type f | sort
    )
    if [ "${#built[@]}" -ne 1 ]; then
        echo "expected exactly one shaded $artifact jar in $module/target, found ${#built[@]}: ${built[*]-}" >&2
        exit 1
    fi
    cp "${built[0]}" "$destination/"
    echo "Staged $(basename "${built[0]}") for connector '$id'"
done

# What the destination has to satisfy, asserted here rather than left to fail later as something
# that reads like a product bug. Two consumers read this directory by two different rules, so both
# are checked, each against the list it applies to.
#
# catalog-derive resolves a jar by module name. That rule holds for every caller, because it is the
# one staging above just satisfied.
for module in "${CONNECTOR_MODULES[@]}"; do
    artifact="$(basename "$module")"
    staged="$(find "$destination" -maxdepth 1 -name "$artifact-*.jar" -type f | wc -l | tr -d ' ')"
    if [ "$staged" -ne 1 ]; then
        echo "$destination resolves $staged jars for module '$artifact', and derive requires exactly one" >&2
        exit 1
    fi
done

# The witnesses resolve by connector id instead, and an id is only its jar's prefix for some
# connectors: the three this lane drives, yes; most of the catalog, no - 'aliyun-db-mongodb' is built
# by aliyun-mongodb-connector. So this half is asserted for the lane it belongs to, which is the one
# taking the default list. A catalog refresh names its own list and is read by derive, not by the
# witnesses; over its dist the id rule is ambiguous by construction, since siblings that share a
# prefix (mysql, mysql-pxc) are all staged on purpose.
if [ -z "$modules_arg" ]; then
    for id in "${CONNECTOR_IDS[@]}"; do
        staged="$(find "$destination" -maxdepth 1 -name "$id*.jar" -type f | wc -l | tr -d ' ')"
        if [ "$staged" -ne 1 ]; then
            echo "$destination resolves $staged jars for '$id', and the witnesses require exactly one" >&2
            exit 1
        fi
    done
fi

echo "Connector jars staged in $destination"
