# tapstate/v1 field reference

Field manual for authoring `*.tap.yml`. The authoritative grammar is
`schema/tapstate-v1.schema.json`; this file is the readable companion. When a
field here and the schema ever disagree, the schema (and `tapstate validate`) win.

Every document begins with:

```yaml
version: tapstate/v1
kind: <source | pipeline | transform | view | serve>
id: <unique-id-no-dots>
```

`id` is unique across the whole workspace and must not contain a dot. An optional
`metadata:` block (`labels:` map + free-text `description:`) may appear on any kind;
it is annotation only, never identity. An optional `experimental:` map is an escape
hatch exempt from the v1 freeze — avoid it in normal authoring.

---

## kind: source

A connection to an external system plus what to read from it. Also serves as a
**target supplier**: a serve `sync` element points at a `kind: source` to write into.

| field | required | meaning |
|---|---|---|
| `connector` | yes | Connector id this source reads/writes through (e.g. `mysql`, `mongodb`, a file connector). |
| `config` | — | Connector connection config; keys are connector-specific. Values may use `${ENV_VAR}`. |
| `mode` | — | Source read mode: `cdc`, `snapshot`, `stream`, `file`, or `api`. May be omitted when the source is only a connection supplier (e.g. a pure target). |
| `tables` | — | What to read: bare names (`[orders, customers]`), `/regex/` patterns, or per-table objects. |
| `options` | — | Connector-specific source options. |
| `srs` | — | Shared Record Store configuration; only valid on `cdc` sources. |

```yaml
version: tapstate/v1
kind: source
id: src_file
connector: e2e_file
config: { uri: "${SRC_DIR}" }
mode: cdc
tables: [ orders ]
```

A target supplier is just a source with no `mode`/`tables` — only enough `config`
to connect:

```yaml
version: tapstate/v1
kind: source
id: tgt_file
connector: e2e_file
config: { uri: "${TGT_DIR}" }
```

---

## kind: pipeline

The runnable task. Reads `source`(s), applies ordered `transforms`, exposes output
via `serve` and/or `view`.

| field | required | meaning |
|---|---|---|
| `source` | yes | Id (or list of ids) of pre-created sources this pipeline reads. At least one. |
| `transforms` | — | Ordered list of transform steps (see below). |
| `serve` | — | Serve block exposing the output downstream (see `kind: serve`). |
| `view` | — | View block shaping output into a queryable result (see `kind: view`). |
| `settings` | — | Task-level settings (see below). |

### settings

| field | meaning |
|---|---|
| `read_mode` | What to read from a cdc source: `snapshot_and_cdc`, `cdc_only`, or `snapshot_only`. |
| `start_from` | Where to start an incremental tail: `earliest`, `latest`, or an ISO-8601 timestamp. |
| `error_policy` | Record-level error reaction: `dead_letter`, `skip`, or `fail`. |
| `batch_size` | Records processed per batch (integer). |
| `parallelism` | Parallel workers (integer). |
| `schedule` | Cron-style schedule; only valid for a bounded read. |

### inline transforms

Each entry in `transforms:` is a step with an `id`, a `from` (which upstream
alias(es) it consumes), a `type`, and type-specific fields. Types:

| type | required fields | meaning |
|---|---|---|
| `filter` | `expr` | Keep rows where the CEL boolean `expr` is true. |
| `map` | `fields` | Project output fields keyed by name, each by a field rule; declared order is preserved. |
| `js` | `script` | Run JavaScript source per event. |
| `union` | — | Merge multiple upstream streams. |
| `nest` | `root` | Assemble multiple streams into nested documents (see below). |
| `join` | — | Join streams (see the schema for the exact shape). |

```yaml
version: tapstate/v1
kind: pipeline
id: e2e_pipeline
source: src_file
settings: { read_mode: snapshot_and_cdc }
transforms:
  - { id: even_orders, from: [orders], type: filter, expr: "int(after.id) % 2 == 0" }
serve:
  from: even_orders
  sync:
    - source: tgt_file
```

### nest (stateful assembly)

`nest` builds nested documents (a parent with embedded children). Its `root`
anchors the parent stream; `embed` lists the children placed under it.

| `root` field | meaning |
|---|---|
| `from` (required) | Alias of the parent stream. |
| `key` | Upsert key fields identifying a parent document. |
| `mode` | Parent write mode (e.g. append-only or upsert). |
| `embed` | Child streams embedded under each parent. |

| `embed` field | meaning |
|---|---|
| `from` (required) | Alias supplying this child's rows. |
| `on` (required) | Maps the child's join fields to the parent fields they match. |
| `as` (required) | `array` (one-to-many) or `object` (one-to-one). |
| `path` (required) | Target field path under the parent. |
| `arrayKey` | Fields uniquely identifying an element within an embedded array. |
| `ignoreUpdates` | When true, child updates are not propagated into the parent. |
| `trackJoinKeyChanges` | When true, join-key changes move embedded data accordingly. |
| `embed` | Further children beneath this one (recursive tree). |

---

## kind: serve

A publish surface exposed by a pipeline (inline as `serve:` in a pipeline, or a
reusable `kind: serve` document). `from` names the upstream it exposes; then one or
more of:

- **`sync`** — continuously write rows to a target. Each element:

  | field | required | meaning |
  |---|---|---|
  | `source` | yes | Reference to a `kind: source` connection supplier = the target. |
  | `write_mode` | — | How rows are written, e.g. `upsert` or `append`. |
  | `rename` | — | Rename the target table/columns relative to pipeline output. |
  | `ddl` | — | Schema-change policy: `apply`, `ignore`, or `fail` (default `fail`, to avoid silent drift). |
  | `id` | — | Optional; required only when a query references this element. |
  | `options` | — | Connector-owned options. |

- **`query`** — read endpoints exposed for querying the served data.
- **`push`** — push endpoints that stream changes to downstream consumers.

---

## kind: view

A materialized, queryable shape of pipeline output.

| field | meaning |
|---|---|
| `primary_key` | Column used as the view's primary key. |
| `storage` | Where/how the view is materialized (hot / warm / cold tiers). |
| `schema` | Column definitions of the view's output schema. |

---

## kind: transform

A standalone, reusable transform step referenced by pipelines (rather than inlined).
Carries the same body types as inline transforms, plus `options` for
transform-owned extensions. Reach for this only when a step is shared across
pipelines; otherwise inline it.

---

## Expressions

`filter` and `map` transforms use **CEL** (Common Expression Language),
evaluated once per change event.

- The event exposes the record image — most commonly `after.<field>` for the new
  row (e.g. `after.id`, `after.status`).
- Use CEL's built-in functions and operators only (arithmetic, comparison, boolean
  logic, `int()`/`string()` conversions, etc.).
- Example: `int(after.id) % 2 == 0` keeps even-id rows.

When unsure whether a function or field path is supported, check with
`tapstate explain` or validate a small pipeline — do not assume a function exists.

---

## Canonical form

`tapstate` defines the canonical serialization (key ordering, formatting, quoting).
Produce YAML with `tapstate new` rather than hand-tuning layout to look "cleaner".
`tapstate validate` checks semantic validity only, not layout — it will pass a
hand-formatted file that has already drifted from canonical form.
