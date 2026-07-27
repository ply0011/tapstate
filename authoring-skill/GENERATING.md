# Generating a pipeline from a conversation

How to go from "I want to move data from X to Y" to a **valid** set of `*.tap.yml`
files. Read this together with [REFERENCE.md](REFERENCE.md) (field manual) and
`schema/tapstate-v1.schema.json` (exact grammar). Every recipe below was checked
with `tapstate validate`; treat them as starting shapes to adapt, then validate
your own result.

## Step 1 — elicit the intent

Before writing anything, make sure you know these. Ask only for what you cannot
reasonably infer; state the defaults you assume and let the user correct them.

| Question | Why it matters | Maps to |
|---|---|---|
| What system do we read from? | Picks the source connector. | `source.connector`, `source.config` |
| What tables / collections? | Scopes the read. | `source.tables` |
| Full load, changes only, or both? | Read strategy. | `settings.read_mode` |
| What system do we write to? | The target is itself a `kind: source`. | a second `source` + `serve.sync.source` |
| What shape does the target want? | Flat rows vs nested documents decides the transforms. | `transforms` (`filter`/`map`/`nest`/...) |
| Any filtering, renaming, or computed fields? | Adds transform steps. | `filter`, `map` |
| Upsert or append at the target? | Write semantics. | `serve.sync.write_mode` |

If the user is vague, pick the safe default, say so, and move on — do not stall.
Safe defaults: `read_mode: snapshot_and_cdc`, `write_mode: upsert`, no transforms
until a shaping need is stated.

## Step 2 — map intent to resources

- "read from <db>" -> one `kind: source` with `connector`, `config`, `mode`, `tables`.
- "write to <db>" -> a second `kind: source` (target supplier: just `connector` +
  `config`, no `mode`/`tables`), referenced from `serve.sync[].source`.
- "only some rows" -> a `filter` transform (CEL boolean).
- "rename / drop / compute fields" -> a `map` transform.
- "combine two streams of the same shape" -> a `union` transform.
- "one document with its children embedded" -> a `nest` transform.
- The `pipeline` ties it together: `source`, ordered `transforms`, and a `serve`.

## Step 3 — the wiring rule (the most common mistake)

Two different `from` shapes, and they are not interchangeable:

- **Streaming steps** (`filter`, `map`, `js`, `union`) take a **list**:
  `from: [ orders ]` or `from: [ web_events, app_events ]`.
- **`nest` and `join`** take a **named map**: `from: { orders: orders, lines: lines }`
  (alias -> upstream), so `root.from` and `embed.from` can refer to the aliases.

**`serve.from` (and any step's `from`) references a _stream_, not a source id.**
A stream token is a transform **step id**, a bare **table name**, or
**`source_id.table`**. Pointing `serve.from` at a `kind: source` id fails with
`dsl.missing-reference` (see Step 5). With no transforms, serve straight from the
table: `from: orders`.

## Step 4 — recipes (all validated)

### R1 — straight replication (no transform)

```yaml
# orders_src.tap.yml
version: tapstate/v1
kind: source
id: orders_src
connector: mysql
config: { uri: "${MYSQL_URI}" }
mode: cdc
tables: [ orders ]
```
```yaml
# orders_tgt.tap.yml   (target supplier: no mode/tables)
version: tapstate/v1
kind: source
id: orders_tgt
connector: mongodb
config: { uri: "${MONGO_URI}" }
```
```yaml
# replicate.tap.yml
version: tapstate/v1
kind: pipeline
id: replicate
source: orders_src
settings: { read_mode: snapshot_and_cdc }
serve:
  from: orders              # the table stream, not the source id
  sync:
    - source: orders_tgt
      write_mode: upsert
```

### R2 — filtered replication

Add a `filter` step; serve from the step id.

```yaml
transforms:
  - { id: big, from: [orders], type: filter, expr: "double(after.amount) > 100.0" }
serve:
  from: big
  sync:
    - source: orders_tgt
      write_mode: upsert
```

### R3 — reshape fields (map)

Each entry under `fields` is `output_name: <rule>`; a CEL expression string is the
common rule (rename, or compute). Declared order is the output order.

```yaml
transforms:
  - id: shape
    from: [orders]
    type: map
    fields:
      order_id: "after.id"
      total: "double(after.price) * double(after.qty)"
serve:
  from: shape
  sync:
    - source: orders_tgt
```

### R4 — nested documents (nest)

Parent `orders` with child `lines` embedded as an array. Note the **map-form**
`from`, and `on: { child_field: parent_field }`.

```yaml
source: shop_src            # a source whose tables are [ orders, lines ]
transforms:
  - id: nested
    from: { orders: orders, lines: lines }
    type: nest
    root:
      from: orders
      key: [ id ]
      embed:
        - from: lines
          on: { order_id: id }   # lines.order_id matches orders.id
          as: array              # or: object (one-to-one)
          path: lines            # target field under the parent
serve:
  from: nested
  sync:
    - source: orders_tgt
      write_mode: upsert
```

### R5 — merge same-shaped streams (union)

```yaml
source: evt_src             # tables: [ web_events, app_events ]
transforms:
  - { id: merged, from: [ web_events, app_events ], type: union }
serve:
  from: merged
  sync:
    - source: orders_tgt
```

### Renaming target tables

A `serve.sync` element can rename what it writes, without a `map`:

```yaml
sync:
  - source: orders_tgt
    write_mode: upsert
    rename: { prefix: "stg_", case: lower }   # also: suffix, or an explicit map:
```

## Step 5 — validate and iterate on coded errors

Write the files, then `tapstate validate <dir>`. On a non-zero exit you get a
**coded error** — the code names the failure class, the message names the fix.
Do not guess; read it. Example:

```
invalid: (batch)  dsl.missing-reference
  Reference 'orders_src' at serve.from points to nothing in this workspace.
  Define the referenced resource, or fix the name to match an existing one.
```

Here the fix is Step 3: `serve.from` wanted a stream (`orders`), not the source id.
Correct the file and validate again. Repeat until you see `valid: N resources`.

## Pitfalls

- **Invent nothing.** Only connectors, fields, transform `type`s, and enum values
  that appear in the schema. The validator rejects the rest and it costs a round trip.
- **`id` must not contain a dot** — dots are reserved for `source_id.table` refs.
- **The target is a `source`**, referenced by `serve.sync[].source`. There is no
  separate "sink" kind.
- **Secrets via `${ENV}`** in `config`, never inlined credentials.
- **Do not hand-tune layout.** Prefer `tapstate new`; canonical form is the tool's
  to decide, and `validate` confirms it.
