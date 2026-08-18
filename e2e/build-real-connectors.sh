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
#   e2e/build-real-connectors.sh /tmp/connectors
#   mvn -pl e2e -am verify -Dapi.version=1.44 -Dtapstate.e2e.connectors-dir=/tmp/connectors \
#     -Dit.test=PublishedExamplesIT
#
# A second argument names the directory to clone into, which has to be empty; it defaults to a fresh
# temporary directory.

set -euo pipefail

readonly SOURCE_REPOSITORY="https://github.com/tapdata/tapdata-connectors.git"

# Connector ids as the specifications name them, paired with the module that builds each one. The id
# is also the file-name prefix the witnesses resolve by, so it cannot be chosen freely here.
readonly CONNECTOR_IDS=(mysql mongodb postgres)
readonly CONNECTOR_MODULES=(connectors/mysql-connector connectors/mongodb-connector connectors/postgres-connector)

destination="${1:?usage: build-real-connectors.sh <destination-directory>}"
workspace="${2:-$(mktemp -d)}"

mkdir -p "$destination"
destination="$(cd "$destination" && pwd)"

echo "Cloning the connector source into $workspace"
git clone --depth 1 --quiet "$SOURCE_REPOSITORY" "$workspace/tapdata-connectors"

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
    bom="$workspace/tapdata-connectors/connectors-common/debezium-bucket/debezium-bom/pom.xml"
    protoc_version="$(sed -n 's|.*<version\.com\.google\.protobuf>\(.*\)</version\.com\.google\.protobuf>.*|\1|p' \
        "$bom" | head -1)"
    if [ -z "$protoc_version" ]; then
        echo "cannot read the pinned protoc version out of $bom" >&2
        exit 1
    fi
    protoc_base="https://repo1.maven.org/maven2/com/google/protobuf/protoc/$protoc_version"
    if curl -fsI "$protoc_base/protoc-$protoc_version-osx-aarch_64.exe" >/dev/null 2>&1; then
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
echo "Building $modules"
# exec.skip turns off one upstream build step: the postgres connector, alone among every module in
# that repository, runs an encryptor over its own shaded jar at package time. The result is not a zip
# - it has no end-of-central-directory record - and this product opens a connector artifact with
# java.util.jar.JarFile to read its specification before anything else happens. So a stock postgres
# jar cannot be registered at all: it fails at the first read, long before reaching the PDK runtime
# that knows how to decrypt it. Building it unencrypted produces the same shape the other connectors
# already ship in, which is the shape this product reads.
#
# Scoped by luck rather than by design, so it is worth stating: this flag turns off exec-plugin
# executions across the whole build, and the only active one in this module set is that encryptor
# (mysql declares the plugin but its execution is commented out upstream). If a future connector
# joins this lane with a real exec step, this becomes too blunt and has to move to a postgres-only
# invocation.
# The odd expansion keeps an empty array from tripping set -u on the bash a Mac ships.
mvn -B -f "$workspace/tapdata-connectors/pom.xml" -pl "$modules" -am -DskipTests -Dexec.skip=true \
    ${protoc_flags[@]+"${protoc_flags[@]}"} package

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
        find "$workspace/tapdata-connectors/$module/target" -maxdepth 1 -name "$artifact-v*.jar" -type f | sort
    )
    if [ "${#built[@]}" -ne 1 ]; then
        echo "expected exactly one shaded $artifact jar in $module/target, found ${#built[@]}: ${built[*]-}" >&2
        exit 1
    fi
    cp "${built[0]}" "$destination/"
    echo "Staged $(basename "${built[0]}") for connector '$id'"
done

# The destination is what the witnesses are pointed at, so assert the property they depend on here
# rather than letting an ambiguous directory fail later as something that reads like a product bug.
for id in "${CONNECTOR_IDS[@]}"; do
    staged="$(find "$destination" -maxdepth 1 -name "$id*.jar" -type f | wc -l | tr -d ' ')"
    if [ "$staged" -ne 1 ]; then
        echo "$destination resolves $staged jars for '$id', and the witnesses require exactly one" >&2
        exit 1
    fi
done

echo "Connector jars staged in $destination"
