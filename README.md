# tapstate — preview

## Build one current business view from data that changes across operational systems

Orders, accounts, inventory, and customer state rarely live in one database. tapstate is
an open-source **unified operational data engine** designed to turn database log
changes into continuously maintained, application-ready state.

**Capture. Transform. Serve. One deployable.**

```text
database log changes -> incremental transform and consolidation -> live operational state
                                                               -> applications and AI agents
```

Instead of operating separate CDC, event-streaming, stream-processing, and serving
products, tapstate is designed to provide one data path and one operational surface.

### Try the runnable preview

The current public preview runs a deliberately narrow, single-source path:
MySQL snapshot plus CDC → map transform → MongoDB-backed preview store.

```sh
curl -sSL https://install.tapstate.dev | sh
```

The script creates an isolated `tapstate-demo` directory, verifies snapshot
materialization, and prints the commands for exercising a CDC update. The runtime is
an early, single-node preview—not production-ready. It does not yet demonstrate
cross-source consolidation. See [Project status](#project-status) and the
[full quickstart](docs/quickstart-online.md) before evaluating it for a workload.

> From the team behind TapData, hardened through years of production CDC and real-time data movement work.

## Why tapstate

Modern systems increasingly depend on fresh operational data:

- Applications need current account, order, inventory, entitlement, and customer state.
- AI agents need up-to-date business context, not yesterday's warehouse snapshot.
- Data teams need lower-latency pipelines without adding more infrastructure to operate.
- Platform teams need correctness, recovery, and observability across the whole path.

The common answer is a hand-assembled stack:

```text
Debezium + Kafka / Redpanda + Flink / custom jobs + Redis / MongoDB / Elasticsearch
```

That stack can work, but it creates multiple failure surfaces, upgrade cycles, schemas, offsets, dashboards, and on-call paths.

tapstate brings that source-to-serve path under one product boundary. The current
preview implements a deliberately narrow slice of this direction; see
[Project status](#project-status) before evaluating it for a workload.

## Product direction

Capture, transform, and serve define tapstate's product boundary. They do not imply
that every capability below is available in the current preview.

### Capture

tapstate reads changes from production databases using log-based CDC.

- Initial load plus continuous sync
- Change metadata and ordering required to maintain downstream state
- Minimal source impact

### Transform

tapstate reshapes changes incrementally before they are served.

- Filter and route change streams
- Enrich and denormalize records
- Combine related operational data into application-ready views
- Maintain those views as source records change
- Avoid running a separate stream-processing cluster

### Serve

Serve covers both pull and push consumption of maintained operational state.

- Materialize current state into a serving store
- Query current state through standard drivers and, in future releases, data APIs
- Subscribe to state changes through future webhook and messaging adapters
- Reuse the same state across applications, automation, and AI agents

The public preview currently demonstrates materialization to MongoDB. A stable
tapstate State Data API and push/subscription delivery are roadmap capabilities,
not current guarantees.

## Try the current preview

Run the preview locally as a Docker Compose stack. The quickstart brings up MySQL,
the tapstate server, and a MongoDB-backed preview store, then drives a real snapshot
and CDC flow. One command does the whole flow in a `tapstate-demo` directory it
creates:

```sh
curl -sSL https://install.tapstate.dev | sh
```

Prefer to read it first? Download, read, then run — same script, and it works in
the directory you saved it in:

```sh
mkdir tapstate-demo && cd tapstate-demo
curl -sSL https://install.tapstate.dev -o quickstart.sh
sh quickstart.sh
```

Everything it installs stays inside that one directory; tearing down is
`docker compose down -v` in it, and `rm -rf` of the directory removes the rest.
See [docs/quickstart-online.md](docs/quickstart-online.md) for the full walkthrough
and the manual `docker compose` steps behind it.

> **Preview.** The runtime is single-node and not production-ready. Runtime state is
> in memory, and a restart replays from the source rather than resuming a durable
> offset. High availability, durable resume, and exactly-once guarantees are not
> available. `quickstart.sh` downloads a released CLI and server image. See
> [Limitations](docs/quickstart-online.md#limitations).
>
> **Recommended platform.** Docker with Compose v2, plus the system versions each
> release names in its notes: a macOS version, and a glibc version on Linux. Both are
> measured from the binaries themselves and follow the machines that built them, so an
> older system may refuse to launch them — `sw_vers -productVersion` and `ldd --version`
> say what a machine has. Nothing stops you from installing anyway: the installer names
> what the build expects and carries on, and how it goes from there is yours.

To install just the CLI — the offline authoring loop, one native binary, no Docker,
no JDK:

```bash
curl -sSL https://install.tapstate.dev/cli | sh
```

It installs a preview build to `~/.tapstate/bin`, verifies the download against its
published checksum, and never asks for sudo or edits your shell configuration. Prefer
to read before you run — or to skip the pipe entirely? Both are supported:

```bash
# Audit the script first, then run the copy you read:
curl -sSL https://install.tapstate.dev/cli -o install.sh && less install.sh && sh install.sh

# Or skip the installer: download a release asset and check it yourself.
# Assets are tapstate-<version>-<os>-<arch>.tar.gz, each next to its .sha256
# (and all of them listed in the release's checksums.txt):
v=0.1.0 p=darwin-arm64   # your version, and your platform: darwin|linux, arm64|x64
curl -sSLO "https://github.com/tapstate/tapstate/releases/download/v$v/tapstate-$v-$p.tar.gz"
curl -sSLO "https://github.com/tapstate/tapstate/releases/download/v$v/tapstate-$v-$p.tar.gz.sha256"
shasum -a 256 -c "tapstate-$v-$p.tar.gz.sha256"   # sha256sum -c on Linux
tar -xzf "tapstate-$v-$p.tar.gz"
```

To uninstall: `rm -rf ~/.tapstate`, and drop the `PATH` line from your shell
configuration if you added one.

The checked-in walkthrough uses the current `tapstate/v1` DSL and verifies the
materialized state directly in MongoDB. See
[the online quickstart](docs/quickstart-online.md) for the complete resources,
commands, and CDC verification loop.

## Target use cases

- Real-time customer and account 360
- Operational data APIs
- Core system offloading
- Live master data
- Real-time analytics feeds
- Fresh context for AI agents
- Inventory, order, entitlement, and risk state

## Where tapstate fits

These approaches solve different parts of the path from systems of record to current,
application-ready state. The useful distinction is not a feature count; it is which
team or product owns each boundary.

| Approach | Where it fits well | Source capture ownership | Cross-system consolidation and served current state | Deployment and operating boundary | Available in this preview |
| --- | --- | --- | --- | --- | --- |
| Assembled CDC + event stream + processing jobs + serving store | Maximum component choice and independent scaling or replacement | CDC connector or service | Processing jobs combine streams; a separately selected store serves the result | Your team integrates and operates the contracts between components | Not packaged by tapstate; these are the components tapstate aims to bring under one product boundary |
| Streaming database or materialized-view engine | Stateful stream processing and continuously updated views, especially when sources already arrive as streams | Commonly an upstream CDC or messaging layer | Engine maintains views; serving model depends on the product and chosen sink or query surface | Engine plus upstream capture and any external serving infrastructure | Not part of the tapstate preview |
| Managed CDC / ELT service | Low-operations movement from supported sources into a destination | Managed service | Usually destination-oriented; consolidation and operational serving remain in the destination or another system | Provider operates capture and delivery; you own downstream modeling and serving choices | Not part of the tapstate preview |
| Warehouse-centric design | Historical analysis, governance, and reuse of analytical models | Ingestion or ELT layer | Warehouse transformations consolidate data; operational serving usually needs an additional path or accepts warehouse query semantics | Warehouse service plus ingestion, transformation, and any operational-serving layer | Not part of the tapstate preview |
| tapstate direction | One source-to-serve product boundary for maintained operational state | Built-in log-based CDC | Incremental transforms are intended to consolidate state and expose it through pull and push surfaces | One deployable product experience and one operational surface; not a claim of one binary | The current preview verifies MySQL snapshot + CDC, a map transform, and MongoDB materialization; cross-source consolidation and tapstate query/push surfaces are not yet demonstrated |

Kafka remains useful for durable event distribution and decoupled consumers.
Warehouses remain the right home for historical analysis. tapstate focuses on the
operational path that turns database truth into maintained state for applications
and agents.

## Capability contract

The product boundary covers four operational responsibilities: capture, the streaming
data path, incremental transformation, and serving maintained state. **One
deployable** means those responsibilities are designed and operated as one product
experience. It does not mean the whole system is one process or one binary. The
preview is a Docker Compose deployment that includes the tapstate server and MongoDB;
only the offline CLI is a standalone native binary.

### Verified in the current preview

The checked-in quickstart and CLI demonstrate:

- Offline authoring and validation of `tapstate/v1` resources
- A single-node preview runtime
- MySQL initial load plus CDC
- An in-flight map transform
- Continuous materialization to a MongoDB reference backing store
- Pipeline status, preview metrics, and node-local logs

### Product direction—not current capability

Alpha+ work is intended to add cross-source order-state consolidation, a
Tapstate-managed **preview** store experience, and administrative inspection through
the Data Browser, terminal watch experience, and MCP read tools. These remain planned
until their producing workstreams pass acceptance and the release evidence is
published. Inspection tooling is not part of Serve.

The broader product direction includes pull and push serving surfaces, a stable State
Data API, and correctness-by-construction for maintained operational state. None of
those statements is a guarantee that the capability exists in this preview.

### Not promised in Alpha+

Alpha+ is not a production-readiness milestone. It does not promise:

- High availability or multi-node operation
- Durable incremental resume after restart
- Exactly-once delivery or distributed snapshot consistency
- Backup and restore or disaster recovery
- Upgrade and migration guarantees
- Capacity-scaling guarantees or an SLA
- Complete production monitoring, security hardening, or database lifecycle management

## Project status

tapstate is early and actively evolving. The current public quickstart is the
evidence boundary described above.

Its MongoDB instance is the preview's reference backing store. This does not make
tapstate a MongoDB-only architecture, and the preview does not provide a fully
managed production database service. It is not intended for production-critical
paths.

This repository is for developers, platform engineers, and data infrastructure
teams who want to try the engine, follow development, and help shape the project.

## Building and running from source

The vision above is where tapstate is going; this section is the real, working
path today. You describe the preview's implemented path — sources and pipelines
with inline transforms and MongoDB sync materialization — as small declarative
`.tap.yml` documents, and tapstate moves and reshapes the data. The repository
ships an **offline authoring
CLI** (a single native binary that creates, validates, and explores `.tap.yml`
resources with no server, database, or network) plus an early **preview runtime**
that executes those resources as live pipelines.

### Requirements

- **To run the CLI:** no runtime to install — `tapstate` is a native binary (starts in
  ~30 ms). Each build does carry a recommended system version — a macOS version, or a
  glibc version on Linux — that tracks the machine it was built on and can move between
  releases, so every release names its own in its notes. Installing on an older system is
  allowed: the installer says what the build expects and carries on; whether it launches
  there is then up to you.
- **To build from source:** **Oracle GraalVM for JDK 21** (includes `native-image`)
  and **Maven 3.6+**. A plain JDK 21 is enough to build and run the test suite;
  GraalVM is only needed for the native image.

### Build

From the repository root:

```sh
# Full build + unit tests (needs a JDK 21 toolchain)
mvn verify

# Native CLI binary (needs GraalVM for JDK 21 on JAVA_HOME)
mvn -Pnative -pl cli -am -DskipTests package
```

The native binary lands at **`cli/target/tapstate`** (the connector catalog,
grammar schema, and message text are embedded). That standalone file is enough
for offline authoring. `tapstate mcp` additionally needs the sibling sidecar,
so install the complete bundle when exposing the command globally:

```sh
# Build the native CLI and MCP Boot JAR, then assemble bin/ + libexec/.
mvn -Pnative -pl cli,control/mcp-server,distribution/cli-bundle -am -DskipTests package
tar -xzf distribution/cli-bundle/target/cli-bundle-0.1.0-native.tar.gz
mkdir -p "$HOME/.tapstate/versions/0.1.0" "$HOME/.tapstate/bin"
mv tapstate-cli-0.1.0/* "$HOME/.tapstate/versions/0.1.0/"
ln -s "$HOME/.tapstate/versions/0.1.0/bin/tapstate" "$HOME/.tapstate/bin/tapstate"
export PATH="$HOME/.tapstate/bin:$PATH"
```

The stable entry is a symlink into the versioned bundle. The launcher resolves
that link before locating `libexec`, so an MCP host can invoke `tapstate mcp`
without a repository-relative path. The release installer performs this layout
and switches the stable link atomically; it never scans Maven caches or `PATH`
for a sidecar.

Verify:

```console
$ tapstate --version
tapstate 0.1.0
```

### Quick start (offline CLI)

The offline workspace is an ordinary folder, partitioned by resource kind. Create
a source, wire it into a pipeline, and validate — all without a server:

```console
$ tapstate new -y --kind source --connector mysql --id orders_src -m cdc
created source/orders_src.tap.yml

$ tapstate new -y --kind source --connector postgres --id warehouse -m cdc
created source/warehouse.tap.yml

$ tapstate new -y --kind pipeline --id orders_sync --source orders_src --sync-to warehouse
created pipeline/orders_sync.tap.yml

$ tapstate validate
valid: 3 resources
```

Each `new` writes a deterministic, canonical `.tap.yml`:

```yaml
version: tapstate/v1
kind: source
id: orders_src
connector: mysql
mode: cdc
```

`validate` runs three offline checks — **structure** (schema: unknown fields /
types / enums), **reference closure** (referenced ids exist), and **capability
matrix** (mode × connector legality) — and every diagnostic carries a code, a
location, and a fix suggestion. Browse and document your workspace with `ls`,
`desc <id>`, and `explain <field>`; run `tapstate` with no arguments for an
offline REPL with Tab completion. All five offline verbs (`new` / `validate` /
`ls` / `desc` / `explain`) accept `-o json|yaml` for machine-readable output.

### Editor integration

The grammar ships as a standard JSON Schema (draft 2020-12) in the tree at
`core/core-schema/src/main/resources/schema/tapstate-v1.schema.json`. Associate
`*.tap.yml` with it for live validation, completion, and hover docs in any editor
backed by [yaml-language-server](https://github.com/redhat-developer/yaml-language-server):

```jsonc
// VS Code settings.json
{
  "yaml.schemas": {
    "/abs/path/to/tapstate/core/core-schema/src/main/resources/schema/tapstate-v1.schema.json": "*.tap.yml"
  }
}
```

### Preview runtime

Beyond the offline verbs, `connect` / `login` / `register` / `apply` /
`discover-schema` / `start` / `status` / `metrics` / `logs` (and friends) drive a
running tapstate server — see [docs/quickstart-online.md](docs/quickstart-online.md)
for the end-to-end flow (build → start a server → run a real sync). That runtime is
an early **preview** (single-node, in-memory); offline, without a connection, those
verbs exit with code `3`.

## Roadmap

- [x] Public quickstart
- [ ] Supported source and target matrix
- [ ] Architecture guide
- [ ] Local development environment
- [ ] Benchmarks
- [ ] Deployment examples
- [ ] Contributor guide

## Documentation

- [Quickstart](docs/quickstart-online.md)
- Architecture: coming soon
- Connectors: coming soon
- Deployment: coming soon
- FAQ: coming soon

## FAQ

### How is tapstate different from CDC, Kafka, Flink, streaming databases, or a warehouse?

They solve different parts of the data path:

- CDC tools capture database changes but do not by themselves consolidate and serve application-ready state.
- Kafka distributes durable event streams and decouples producers from consumers. It remains valuable when events need independent consumers or replay outside tapstate’s product boundary.
- Flink and similar processors provide general-purpose stateful stream computation, typically alongside separate capture, messaging, and serving infrastructure.
- Streaming databases and materialized-view engines maintain continuously updated views, but commonly rely on upstream capture or messaging and have their own query or sink boundaries.
- Warehouses are designed primarily for historical analysis, governance, and analytical workloads—not as the default operational serving path for applications.

tapstate focuses on the complete database-to-operational-state path: capture database
changes, transform and consolidate them incrementally, maintain current state, and
expose that state to operational consumers through one deployable product experience.

It does not claim to replace every use of Kafka, Flink, streaming databases, or
warehouses. Those systems remain appropriate when event distribution, general-purpose
stream computation, or historical analytics is the primary requirement.

### What does “four products in one” mean? Is tapstate one binary?

“Four products in one” refers to four operational responsibilities:

1. **Capture:** read changes from source database logs.
2. **Stream:** carry those changes through an integrated streaming data path.
3. **Transform:** incrementally reshape and consolidate changes into application-ready business state.
4. **Serve:** maintain that current state and make it available to operational consumers through pull and push surfaces.

tapstate brings these responsibilities under one product boundary, deployment
experience, configuration model, and operational surface. It is an alternative to
independently assembling and integrating CDC, Kafka or another event-streaming layer,
Flink or custom processing jobs, and a serving store.

It does not mean the complete system is one process or one binary. The preview is
deployed with Docker Compose and includes the tapstate server and MongoDB reference
backing store. Only the offline CLI is a standalone native binary.

“One deployable” therefore means one integrated product experience—not one executable
file.

### Whose MongoDB is it, and what does tapstate manage?

Alpha+ includes a single-node MongoDB replica set as tapstate’s reference backing
store. Users do not need to install MongoDB separately, provide a MongoDB URI, or
configure its namespaces and indexes for the demo path.

Within this preview experience, tapstate manages:

- Bootstrap and connection configuration
- Readiness checks
- Internal database and collection namespaces
- Indexes required by tapstate-maintained state
- Startup and teardown as part of the local deployment

This is a **Tapstate-managed preview store**, not a production MongoDB service.
Alpha+ does not promise high availability, backup and restore, upgrades, migration,
capacity management, comprehensive monitoring, security hardening, or an operational
SLA.

MongoDB is the reference backing store because its document model naturally
represents the nested business objects tapstate maintains, such as an order containing
shipment state. This choice does not make tapstate a MongoDB-only architecture.

Using a customer-managed external MongoDB—or another external store—is a future
deployment option, not part of the Alpha+ promise.

### How will tapstate handle recovery and correctness, and what works in Alpha+?

Tapstate’s intended production model is durable, correctness-oriented state
maintenance. A production-capable runtime should:

- Persist source progress and resume incrementally after restart
- Reconstruct maintained state deterministically
- Resolve cross-source updates according to source positions and declared transformation semantics
- Detect and recover from interrupted processing
- Provide explicit, testable delivery and consistency semantics

The goal is correctness-by-construction rather than requiring users to reconcile
corrupted or ambiguous materialized state manually. The precise production
guarantees—including delivery semantics, cross-source consistency boundaries,
recovery behavior, and supported failure modes—will be defined and verified before
they are promised.

**Alpha+ is an earlier implementation of this direction.** Its runtime is single-node
and keeps source progress in memory. After restart, it rebuilds maintained state by
replaying from the source instead of resuming incrementally from a durable offset.

The Alpha+ demo is intended to verify that changes from two source streams converge
into the expected order state, including when one stream is deliberately delayed.
This proves the bounded demo behavior; it does not establish a general production
guarantee.

Alpha+ therefore does not promise exactly-once delivery, transactionally consistent
snapshots across independent databases, durable incremental recovery, or complete
failure-mode coverage.

### What data will tapstate’s query surface serve?

Tapstate’s query surface is intended to serve operational state that tapstate owns
and maintains. Applications, operators, and agents should be able to inspect declared
views without depending on the implementation details or query capabilities of an
external destination.

External sync targets have a different role. They are delivery destinations for data
that users want to consume and manage in their own systems. They are not query backends
behind tapstate’s query interface.

Keeping these boundaries separate gives tapstate control over the semantics of its
maintained state and query surface. Otherwise, behavior would vary according to each
external database’s indexing, query language, consistency model, permissions, and
availability.

**In Alpha+, query is deliberately narrow.** It only serves declared views in the
Tapstate-managed preview store. A user cannot configure an arbitrary MySQL, Oracle,
MongoDB, or another sync target as the backend for a tapstate query.

Alpha+ provides administrative and evaluation access through the planned Data
Browser, terminal watch experience, and MCP read tools. These are inspection tools,
not Serve. A stable application-facing State Data API remains future work.

### What does “Serve” mean, and is it available in Alpha+?

Serve is tapstate’s intended application-facing delivery layer for maintained
operational state. It will support two consumption models:

- **Pull:** applications and agents request current state through stable data APIs and supported query interfaces.
- **Push:** tapstate delivers state changes through webhooks, Kafka or other messaging systems, downstream databases, and subscription interfaces.

Both models are intended to operate from the same Tapstate-maintained state and
transformation semantics. This is the fourth responsibility in **Capture / Stream /
Transform / Serve**.

**Alpha+ does not validate or deliver Serve.** Its Data Browser, terminal watch
experience, and MCP read tools are administrative, development, and evaluation
surfaces for inspecting the preview store. They are not stable application-facing
serving interfaces and should not be used as evidence that Serve has shipped.

Stable data APIs, production application queries, subscriptions, webhooks, messaging
adapters, and downstream push delivery remain future capabilities.

### How is tapstate related to TapData, and why try it before production readiness?

tapstate is an open-source product created by the team behind TapData and builds on
the team’s experience with enterprise CDC and real-time data movement.

The products are designed around different primary jobs:

- **TapData** primarily addresses data replication and movement: reliably capturing changes and delivering data from source systems to downstream destinations.
- **tapstate** is intended to provide a complete operational-state offering: **Capture / Stream / Transform / Serve** within one product boundary, continuously turning changes across systems into maintained, application-ready business state.

tapstate is therefore not simply an open-source edition or renamed version of TapData.
It expands the product boundary beyond replication: the outcome is maintained
operational state, not only delivery into another database.

Trying Alpha+ is useful for teams that want to:

- Evaluate the source-to-state programming model
- Test the cross-source operational-state concept
- Inspect how declared views become maintained state
- Explore the Data Browser and MCP-based development workflow
- Influence future Serve interfaces, deployment models, and correctness semantics

Alpha+ should not be used for production-critical workloads. It lacks high
availability, durable incremental recovery, application-facing Serve interfaces,
production database lifecycle management, complete security and monitoring hardening,
and defined operational guarantees.

The reason to try it now is to evaluate and shape the operational-state
architecture—not to treat the preview as a production replacement for TapData’s
replication capabilities.

## Community

- Website: https://tapstate.com
- GitHub: https://github.com/tapstate/tapstate
- Discussions: coming soon
- Issues: https://github.com/tapstate/tapstate/issues

## Relationship to TapData

tapstate is an open-source unified operational data engine from the team behind
TapData.

TapData is a mature real-time data integration and operational data platform used
in enterprise environments. tapstate focuses on a simpler, developer-first path:
capture, transform, and serve live operational state in one deployable.

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md).

## License

tapstate is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
