---
name: tapstate-authoring
description: Author Tapstate data-integration pipelines by writing tapstate/v1 .tap.yml files. Use when the user wants to build, edit, or explain a Tapstate pipeline, source, transform, view, or serve resource, or mentions .tap.yml, a CDC/snapshot data pipeline, or moving data between databases (MySQL, MongoDB, files) with Tapstate.
---

# Tapstate authoring

Help the user write **`tapstate/v1`** resource files (`*.tap.yml`) that describe a
data-integration pipeline: read from a source, transform the stream, and serve the
result to a target. You author the YAML through conversation; the installed
`tapstate` CLI is the single source of truth for whether it is valid.

## The one rule that keeps you honest

**Never claim a file is valid from memory. Always confirm with the CLI.** After you
write or change any `*.tap.yml`, run:

```
tapstate validate <path>      # a directory of *.tap.yml, or a single file
```

- Exit 0 = the workspace is valid canonical form.
- A non-zero exit prints a **coded error** (a stable `domain.symbol` code plus a
  human message). Read the code and message, fix the file, and validate again.
  Do not guess at fixes — the message names the field and the constraint.

If `tapstate` is not on PATH, tell the user to install it first (the project's
one-line installer) and stop; do not fabricate validation results.

Other CLI verbs you can lean on:

| Verb | Use |
|---|---|
| `tapstate new <kind>` | Scaffold a fresh canonical `*.tap.yml` (source, pipeline, transform, view, serve). Start here rather than typing YAML from scratch. |
| `tapstate explain <field.path>` | Authoritative, version-matched field help, backed by the same schema bundled here. Prefer it over guessing a field's meaning. |
| `tapstate ls [kind]` | List the resources already in the workspace. |
| `tapstate desc <id>` | Summary, validation status, and references for one resource. |

## The model in one minute

A workspace is a set of `*.tap.yml` documents. **One document holds exactly one
resource.** Every document starts with `version: tapstate/v1` and a `kind:`. The
top-level `id` is the resource's identity — unique across the whole workspace, and
it must not contain a dot.

Five kinds:

| kind | role |
|---|---|
| `source` | A connection to a system (via a connector such as `mysql`, `mongodb`, or a file connector) and the tables/streams to read. Also used as a **target** supplier: a serve `sync` points at a `kind: source` to write into it. |
| `pipeline` | The task. Names one or more `source`s, an ordered list of `transforms`, and a `serve` (and/or `view`) block. This is the thing that runs. |
| `transform` | A reusable, standalone transform step referenced by pipelines. Inline transforms inside a pipeline are the common case; a `kind: transform` document is for sharing one across pipelines. |
| `view` | A queryable, materialized shape of pipeline output (primary key, storage tier, column schema). |
| `serve` | A reusable publish surface (sync / query / push) referenced by pipelines. |

Data flows **source -> transforms -> serve/view**. A minimal pipeline references a
source, optionally filters/maps it, and syncs the result into a target source.

## How to author (the loop)

For the full conversation-to-YAML playbook — an elicitation checklist, intent ->
resource mapping, and validated recipes (straight replication, filter, map, nest,
union) — follow [GENERATING.md](GENERATING.md). The loop in short:

1. **Understand the intent** — what system to read, what to write, what shape the
   target wants (flat rows? nested documents?).
2. **Scaffold, don't freehand** — prefer `tapstate new <kind>` to get a canonical
   skeleton, then fill it in. Canonical form (key order, formatting) is defined by
   the tool, not by you; do not hand-optimize layout.
3. **Fill fields against the reference** — see [REFERENCE.md](REFERENCE.md) for the
   field manual of every kind, and `schema/tapstate-v1.schema.json` for the exact
   grammar. When unsure about one field, `tapstate explain <field.path>`.
4. **Validate** — `tapstate validate <path>`. Fix on coded errors. Repeat until
   clean.
5. **Show the user** the validated files and a one-line explanation of each.

## Guardrails

- **Stay inside `tapstate/v1`.** Only use fields that appear in the bundled schema.
  Do not invent fields, connectors, transform types, or enum values; the validator
  will reject them, and inventing them wastes a round trip.
- **CEL expressions** (in `filter` / `map` transforms) evaluate per record against
  the change event — e.g. `after.<field>` for the new row image. Keep them to the
  documented functions; see [REFERENCE.md](REFERENCE.md#expressions).
- **Secrets and environment** — connector `config` values may reference environment
  variables as `${VAR}`. Prefer that over inlining credentials into the YAML.
- **You produce YAML, not side effects.** This skill is offline authoring only:
  it writes and validates local files. It does not connect to a running server,
  register connectors, or start tasks.

## What's in this bundle

```
tapstate-authoring/
├── SKILL.md                         (this file — the loop and the model)
├── GENERATING.md                    (conversation -> valid YAML: elicitation + validated recipes)
├── REFERENCE.md                     (field manual for all five kinds + expressions)
├── schema/
│   └── tapstate-v1.schema.json      (the exact grammar; same schema the CLI validates against)
└── examples/
    └── *.tap.yml                    (worked, validated examples)
```

Read `REFERENCE.md` when you need a specific field; read a file under `examples/`
when you want a known-good shape to adapt.
