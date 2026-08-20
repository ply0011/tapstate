#!/bin/sh
#
# tapstate quickstart — brings up the local demo from an empty directory: it downloads the compose stack,
# the platform's CLI, and the demo connector jars, generates a demo workspace and a .env with a random
# admin password, then starts the stack and runs a real MySQL -> MongoDB pipeline. Nothing is built.
#
# Usage, either form:
#
#   curl -sSL <base>/quickstart.sh | sh
#
# Piped, the script takes a directory of its own (./tapstate-demo) so everything it adds stays inside
# one removable directory. Download-then-run works the same and is the form to pick when you want to
# read the script first, re-run it, or inspect a failure -- the saved file marks the directory to
# work in, so nothing nests:
#
#   mkdir tapstate-demo && cd tapstate-demo
#   curl -fLO <base>/quickstart.sh
#   sh quickstart.sh
#
# It never uses sudo, never edits a shell rc, and installs the CLI in place (./tapstate, not on PATH), so
# `rm -rf` of this directory removes everything it added. An unsupported platform fails before anything is
# fetched, leaving this directory as you found it.
#
# Environment seams:
#   TAPSTATE_QUICKSTART_BASE_URL  where install.sh, the compose file, and the seed SQL are fetched from.
#   TAPSTATE_BASE_URL             CLI release base, passed through to install.sh.
#   TAPSTATE_VERSION              pin the CLI version (default: the pinned CLI_VERSION below).
#   TAPSTATE_CONNECTORS_URL       base URL for the demo connector jars.
#   TAPSTATE_QUICKSTART_PREPARE_ONLY  stop after preparing the directory, before Docker (used by tests).
#
# POSIX sh, no bashisms. All work is inside main().
set -eu

# The CLI release this quickstart installs. install.sh's own default resolves /releases/latest, and
# GitHub fills that only from full releases -- the CLI ships as a prerelease, so that lookup finds
# nothing and install.sh refuses, which would strand the quickstart at the CLI step on a clean machine.
# Pin it here instead, the same way the demo connector jars are pinned to a published tag. This must
# match the version in pom.xml; quickstart-smoke.sh fails the build when the two drift apart.
CLI_VERSION="0.2.1"

die() {
    printf 'quickstart: %s\n' "$1" >&2
    exit 1
}

# Download $1 to the file $2 with whichever of curl / wget is present.
fetch() {
    if command -v curl >/dev/null 2>&1; then
        curl -fsSL "$1" -o "$2"
    elif command -v wget >/dev/null 2>&1; then
        wget -q "$1" -O "$2"
    else
        die "neither curl nor wget is available to download $1."
    fi
}

# Generate the demo workspace: a source, a target, and the pipeline joining them, ready to apply. The
# addresses are compose service names because the connector runs inside the server container, where
# loopback is the server itself. The heredocs are quoted so $customer stays the literal DSL rename token
# it is, not a shell variable. This mirrors the online walkthrough's sample on purpose -- one sample, not
# two that drift. The pipeline carries every change through, deletes included: a map leaves a delete (which
# has no after image) untouched, so the sink removes the row by key. The decimal `amount` column is only
# ever passed through, never named in a CEL: numeric columns have no CEL overload in this preview.
# One collection's row count in the target, as a plain integer. A read that fails, or answers anything
# that is not a number, counts as zero: this is polled while the stack is still settling, and a
# half-started mongosh has to read as "not there yet" rather than abort the run.
count_target() {   # $1 = collection name
    _c="$(docker compose exec -T mongo mongosh --quiet \
        'mongodb://mongo:27017/warehouse?directConnection=true' \
        --eval "db.$1.countDocuments()" 2>/dev/null | tr -d '[:space:]')"
    case "$_c" in ''|*[!0-9]*) _c=0 ;; esac
    printf '%s' "$_c"
}

generate_workspace() {
    mkdir -p work/source work/pipeline
    cat > work/source/db_src.tap.yml <<'YAML'
version: tapstate/v1
kind: source
id: db_src
connector: mysql
config: { host: mysql, port: 3306, database: appdb, username: root, password: secret }
mode: cdc
tables: [ orders ]
YAML
    # The second engine's source. Two settings are spelled differently from the MySQL source above, and
    # both are this connector's own spelling rather than a choice: the account is `user` where MySQL says
    # `username`, and a table is addressed by schema as well as by database. `mode: cdc` is what makes a
    # row inserted after the stack is up cross at all - a snapshot-only source would carry the seeded
    # rows and then nothing, which reads from outside as a demo that works.
    cat > work/source/db_shipments.tap.yml <<'YAML'
version: tapstate/v1
kind: source
id: db_shipments
connector: postgres
config: { host: postgres, port: 5432, database: appdb, schema: public, user: postgres, password: secret }
mode: cdc
tables: [ shipments ]
YAML
    cat > work/source/warehouse.tap.yml <<'YAML'
version: tapstate/v1
kind: source
id: warehouse
connector: mongodb
config: { isUri: true, uri: "mongodb://mongo:27017/warehouse?directConnection=true" }
YAML
    cat > work/pipeline/sync_orders.tap.yml <<'YAML'
version: tapstate/v1
kind: pipeline
id: sync_orders
source: db_src
settings: { read_mode: snapshot_and_cdc }
transforms:
  - id: shape_orders
    from: [ orders ]
    type: map
    fields:
      customer_name: $customer
      label: "=after.customer + ' <' + src + '>'"
view:
  id: order_state
  from: shape_orders
  primary_key: id
YAML
    # The second engine's half, into the same target: two engines, one warehouse. Assembling the two
    # into a single object is a separate thing and is not this - here they simply both arrive, which is
    # what makes "insert a row in PostgreSQL and watch it appear" something a reader can actually do.
    #
    # The map names only `carrier` and `status`, both text columns. Numeric columns have no CEL overload
    # in this preview, so `id` and `order_id` are passed through untouched rather than named - the same
    # rule the orders pipeline follows for its decimal `amount`.
    cat > work/pipeline/sync_shipments.tap.yml <<'YAML'
version: tapstate/v1
kind: pipeline
id: sync_shipments
source: db_shipments
settings: { read_mode: snapshot_and_cdc }
transforms:
  - id: shape_shipments
    from: [ shipments ]
    type: map
    fields:
      route: "=after.carrier + ' -> ' + after.status"
serve:
  from: shape_shipments
  sync:
    - source: warehouse
YAML
}

# Closing instructions: how to watch the pipeline, exercise CDC, and remove everything. The teardown is
# printed because "back to a clean machine" is only honest if the images are called out too. The CDC
# section walks insert, update and delete -- the pipeline carries all three -- and each read retries for a
# second or two so a change still in flight is never misread as a change that did not happen.
print_next_steps() {
    demo_dir="$(basename "$PWD")"
    uri="mongodb://mongo:27017/warehouse?directConnection=true"
    cat <<EOF
quickstart: pipeline started. The stack is running.

Watch it (from this directory):
  ./tapstate -w work        then: connect http://127.0.0.1:8080 ; login admin ; status sync_orders --watch

See change-data-capture: change the source in MySQL, watch the view in MongoDB follow (run in this
directory). A change reaches the target in about a second, so each read waits for it rather than guessing.

  # insert a row, then wait for it to appear in the target
  docker compose exec mysql mysql -uroot -psecret appdb -e "INSERT INTO orders VALUES (6,'frank',60.00);"
  until docker compose exec -T mongo mongosh --quiet "$uri" --eval 'quit(db.order_state.countDocuments({id:6})?0:1)'; do sleep 1; done
  docker compose exec mongo mongosh --quiet "$uri" --eval 'db.order_state.find({id:6}).pretty()'

  # update it, and watch the mapped label follow the change
  docker compose exec mysql mysql -uroot -psecret appdb -e "UPDATE orders SET customer='franky' WHERE id=6;"
  until docker compose exec -T mongo mongosh --quiet "$uri" --eval 'quit((db.order_state.findOne({id:6})||{}).customer_name=="franky"?0:1)'; do sleep 1; done
  docker compose exec mongo mongosh --quiet "$uri" --eval 'db.order_state.find({id:6}).pretty()'

  # delete it, and watch it leave the target too
  docker compose exec mysql mysql -uroot -psecret appdb -e "DELETE FROM orders WHERE id=6;"
  until docker compose exec -T mongo mongosh --quiet "$uri" --eval 'quit(db.order_state.countDocuments({id:6})?1:0)'; do sleep 1; done
  echo "row 6 is gone from MongoDB, too"

The second engine, the same way. This is the one worth trying if you only try one: a different
database, reached by a different change mechanism, arriving in the same target collection.

  # insert a shipment in PostgreSQL, then wait for it to appear in the target
  docker compose exec postgres psql -U postgres -d appdb -c "INSERT INTO shipments VALUES (7,4,'ups','pending');"
  until docker compose exec -T mongo mongosh --quiet "$uri" --eval 'quit(db.shipments.countDocuments({id:7})?0:1)'; do sleep 1; done
  docker compose exec mongo mongosh --quiet "$uri" --eval 'db.shipments.find({id:7}).pretty()'

Tear down (run in this directory):
  docker compose down -v     stop the stack and delete its data (a re-run re-registers the connectors)
  cd .. && rm -rf $demo_dir  remove this directory (CLI, jars, workspace, .env)
The pulled images remain; remove them with:  docker image rm <image>
EOF
}

main() {
    # The piped form has no saved file and no stack beside it, so it takes a directory of its own --
    # everything this script adds must stay inside one removable directory. Either marker file says
    # "work here": the saved script is the download-then-run form, the compose file is a re-run of an
    # earlier one (piped re-runs land back in the same directory rather than nesting a second).
    if [ ! -f ./quickstart.sh ] && [ ! -f ./docker-compose.yml ]; then
        mkdir -p tapstate-demo
        cd tapstate-demo
        printf 'quickstart: working in %s\n' "$PWD"
    fi

    # The whole product runs as a compose stack, so a machine without Docker is refused here, before
    # anything is downloaded -- an actionable sentence beats "docker: command not found" three
    # downloads later. The prepare-only test seam deliberately skips this: it exists to stop before
    # Docker, so it must not require it. The CLI alone needs neither; say where to get it.
    if [ -z "${TAPSTATE_QUICKSTART_PREPARE_ONLY:-}" ]; then
        command -v docker >/dev/null 2>&1 \
            || die "Docker is required to run the stack. Install Docker with the Compose v2 plugin, or install only the offline CLI:  curl -sSL https://install.tapstate.dev/cli | sh"
        docker compose version >/dev/null 2>&1 \
            || die "Docker is present but the Compose v2 plugin is not ('docker compose version' failed). Update Docker, or install only the offline CLI:  curl -sSL https://install.tapstate.dev/cli | sh"
    fi

    # Where the stack's assets come from: the same release the CLI is pinned to, derived from that pin
    # rather than named separately. A branch would keep moving after the release, handing a later user a
    # CLI frozen at one version beside a compose file from another -- a mismatch that shows up only on
    # their machine. Deriving it means the release tag decides both, and there is no step to remember.
    qbase="${TAPSTATE_QUICKSTART_BASE_URL:-https://raw.githubusercontent.com/tapstate/tapstate/v${CLI_VERSION}}"

    work="$(mktemp -d)"
    trap 'rm -rf "$work"' EXIT INT TERM

    # Platform gate: fetch install.sh into the throwaway work area and reuse its detection. This refuses
    # an unsupported platform (Windows shell, musl, unknown OS/arch) before anything is written into the
    # demo directory, and shares one copy of the uname mapping rather than duplicating it here.
    fetch "${qbase}/install/install.sh" "$work/install.sh"
    if ! platform="$(sh "$work/install.sh" --print-platform)"; then
        exit 1   # install.sh already said why (musl / Windows / unknown), pointing to WSL or source
    fi

    # Fetch the stack into this directory, each asset only if absent, so a re-run neither re-downloads a
    # verified asset nor overwrites an edit the user made to it. The seed dir is created empty on purpose:
    # a registered jar's bytes live in the store, and the demo registers over the CLI upload path, so the
    # seed stays the documented empty convenience rather than the route registration depends on.
    #
    # Both seed directories are fetched, not just the one the demo pipeline reads. The compose file
    # mounts each of them, and a missing mount source is not an error Docker reports - it creates an
    # empty directory and starts a database with no demo data in it, which then fails much later as a
    # pipeline that reads nothing.
    mkdir -p mysql-init postgres-init connectors
    [ -f ./docker-compose.yml ]              || fetch "${qbase}/deploy/quickstart/docker-compose.yml" ./docker-compose.yml
    [ -f ./mysql-init/01-orders.sql ]        || fetch "${qbase}/deploy/quickstart/mysql-init/01-orders.sql" ./mysql-init/01-orders.sql
    [ -f ./postgres-init/01-shipments.sql ]  || fetch "${qbase}/deploy/quickstart/postgres-init/01-shipments.sql" ./postgres-init/01-shipments.sql

    # Install the CLI in place as ./tapstate, reusing install.sh wholesale (download, checksum, atomic
    # place). TAPSTATE_INSTALL_DIR here is the seam that keeps it out of PATH: `rm -rf` of this directory
    # removes it. install.sh's own stdout (a PATH hint that does not apply in place) is dropped; its
    # errors still surface and abort under set -e.
    if [ ! -x ./tapstate ]; then
        TAPSTATE_INSTALL_DIR="$PWD" TAPSTATE_VERSION="${TAPSTATE_VERSION:-$CLI_VERSION}" \
            sh "$work/install.sh" >/dev/null
        # A binary fetched by a browser carries macOS's quarantine attribute, which blocks it from running
        # until cleared; install.sh's atomic move preserves it. Strip it -- only on macOS, only if xattr
        # is present, and tolerating the case where the attribute was never set.
        case "$platform" in
            darwin-*) if command -v xattr >/dev/null 2>&1; then xattr -d com.apple.quarantine ./tapstate 2>/dev/null || true; fi ;;
        esac
    fi

    # The demo connector jars. These three are what this release registers, and they are fetched so the
    # demo runs without the user choosing. They sit outside connectors/ so the seed dir stays empty.
    #
    # The postgres one is fetched for the same reason its seed is: the compose file has run a postgres
    # service with a seeded shipments table since the second engine was added, and a demo that fetches
    # only two jars can never read it. The half that is missing then is precisely the interesting one -
    # a row a user types into the second engine after the stack is up.
    cbase="${TAPSTATE_CONNECTORS_URL:-${TAPSTATE_BASE_URL:-https://github.com/tapstate/tapstate/releases}/download/connectors-preview}"
    [ -f ./mysql-connector.jar ]    || fetch "${cbase}/mysql-connector.jar" ./mysql-connector.jar
    [ -f ./mongodb-connector.jar ]  || fetch "${cbase}/mongodb-connector.jar" ./mongodb-connector.jar
    [ -f ./postgres-connector.jar ] || fetch "${cbase}/postgres-connector.jar" ./postgres-connector.jar

    # A random admin password replaces the shipped admin/admin default so a stack left running is not
    # trivially reachable. It is written only to .env (readable by this user alone) and announced once
    # here -- never passed as a CLI argument, so it stays out of the process table and shell history. A
    # re-run keeps the existing .env: regenerating it would lock the user out of the admin already
    # bootstrapped against the old password.
    if [ ! -f .env ]; then
        admin_pw="$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 24)"
        printf 'TAPSTATE_ADMIN_USER=admin\nTAPSTATE_ADMIN_PASSWORD=%s\n' "$admin_pw" > .env
        chmod 600 .env
        printf 'quickstart: generated a random admin password, saved to .env: %s\n' "$admin_pw"
    fi

    # Generate the demo workspace, unless one is already here: a re-run must not clobber edits the user
    # made to their resources.
    if [ ! -d work ]; then
        generate_workspace
    fi

    if [ -n "${TAPSTATE_QUICKSTART_PREPARE_ONLY:-}" ]; then
        return
    fi

    # Bring up the stack. The compose file pins the published image, so this pulls rather than builds.
    docker compose up -d

    # Wait until the server container reports healthy -- its image carries the /healthz healthcheck -- so
    # the online verbs are not driven before the server can answer. By then the bootstrap sidecar has
    # created the admin from .env.
    printf 'quickstart: waiting for the stack to become healthy'
    i=0
    while [ "$i" -lt 90 ]; do
        if docker compose ps --format json server 2>/dev/null | grep -q '"Health":"healthy"'; then
            break
        fi
        i=$((i + 1)); printf '.'; sleep 2
    done
    printf '\n'
    docker compose ps --format json server 2>/dev/null | grep -q '"Health":"healthy"' \
        || die "the server did not become healthy in time; inspect it with: docker compose logs server"

    # Drive the online verbs through the REPL, feeding the password on stdin (the login prompt reads the
    # next line) so it is never a process argument or a shell-history entry. Workspace paths resolve
    # against work/, so the jars beside it are ../<jar>.
    #
    # Each capture source is applied on its own first, then discovered, then everything is applied. Both
    # pipelines map a row field, and an expression that reads row fields is refused until the source it
    # reads has a discovered schema -- while a discovery, in turn, needs the source to exist on the
    # server. One apply for all of it therefore cannot succeed in either order: the batch is refused
    # whole, which leaves the sources unapplied and the discoveries with nothing to look at. The second
    # engine is under the same rule as the first, not exempt from it -- its pipeline maps a row field too.
    #
    # Applying a source twice is free; the second apply reports it unchanged.
    admin_pw="$(sed -n 's/^TAPSTATE_ADMIN_PASSWORD=//p' .env)"
    printf 'connect http://127.0.0.1:8080\nlogin admin\n%s\nregister ../mysql-connector.jar\nregister ../mongodb-connector.jar\nregister ../postgres-connector.jar\napply source/db_src.tap.yml\napply source/db_shipments.tap.yml\ndiscover-schema db_src\ndiscover-schema db_shipments\napply\nstart sync_orders\nstart sync_shipments\nexit\n' "$admin_pw" \
        | ./tapstate -w work

    # Snapshot verification, printed automatically: the demo's payoff is a real row count in the target,
    # not an "it should have worked". A fresh snapshot of the seeded rows is quick, but the read still
    # retries so a slow first run is not misreported as an empty target.
    echo 'quickstart: waiting for the snapshot to reach the target'
    seeded_orders=5      # rows the demo seed puts in MySQL
    seeded_shipments=6   # rows the demo seed puts in PostgreSQL
    # Both halves are waited on, and both are named if the wait runs out. A demo wired to only one of
    # its two engines fills that engine's collection and leaves the other empty, so a check on a single
    # count reports it as success - and the half left out would be the second engine, which is the whole
    # reason there is a second one.
    orders=0; shipments=0; i=0
    while [ "$i" -lt 30 ]; do
        orders="$(count_target order_state)"
        shipments="$(count_target shipments)"
        [ "$orders" -ge "$seeded_orders" ] && [ "$shipments" -ge "$seeded_shipments" ] && break
        i=$((i + 1)); sleep 2
    done

    # Falling out of that loop short is a failed run, and it has to be said with a non-zero exit. The
    # REPL above cannot say it: an interactive session does not end because one command was rejected,
    # so it exits 0 whether the verbs took or errored, and set -e sees nothing wrong. This count is the
    # only evidence the script has that a pipeline is moving data. The stack is left standing rather
    # than torn down -- the server log is the next thing to read, and a teardown would take it along.
    { [ "$orders" -ge "$seeded_orders" ] && [ "$shipments" -ge "$seeded_shipments" ]; } \
        || die "the snapshot did not reach the target (orders $orders of $seeded_orders, shipments $shipments of $seeded_shipments); inspect it with: docker compose logs server"
    printf 'quickstart: the target now holds %s orders from MySQL and %s shipments from PostgreSQL\n' \
        "$orders" "$shipments"

    print_next_steps
}

main "$@"
