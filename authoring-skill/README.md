# Tapstate authoring skill — try it with your own agent

This folder is a self-contained **authoring skill**: domain knowledge that teaches
an AI agent to write valid Tapstate pipelines (`*.tap.yml`) by talking to you. It
follows the [Agent Skills](https://agentskills.io) open format, so on agents that
support that format natively you just drop the folder in; on others (like a custom
GPT) you upload the files as knowledge. Both paths are below.

The skill is **offline**: the agent writes YAML, and the `tapstate` CLI on your
machine is the judge of whether it is valid. The agent does not connect to
anything.

---

## 0. Prerequisite: the `tapstate` CLI on your PATH

The self-test loop ends in real validation, so you need the CLI locally. Confirm:

```
tapstate --version
```

If that prints a version, you are set. If not, install the CLI first (see the
project's top-level README for install options) and re-check.

Sanity-check that validation works, using a bundled example:

```
tapstate validate authoring-skill/examples/01-straight-replication
# expected: valid: 3 resources in ...
```

---

## 1a. Load it into a custom GPT (OpenAI)

A GPT cannot read the `SKILL.md` frontmatter as an automatic trigger the way
Agent-Skills-native tools do, so you wire it up by hand — once.

1. Create a new GPT (ChatGPT → **Explore GPTs → Create**, or a Project).
2. Under **Knowledge**, upload these files from this folder:
   - `SKILL.md`
   - `GENERATING.md`
   - `REFERENCE.md`
   - `schema/tapstate-v1.schema.json`
   - the `examples/` files (optional but helpful)
3. Under **Instructions**, paste this:

   > You are a Tapstate pipeline authoring assistant. Use the uploaded files as your
   > source of truth: `SKILL.md` (the authoring loop and resource model),
   > `GENERATING.md` (how to turn a request into YAML, with validated recipes),
   > `REFERENCE.md` (the field manual), and `tapstate-v1.schema.json` (the exact
   > grammar). When I describe a data pipeline, ask only for what you cannot
   > reasonably infer, then produce `tapstate/v1` `*.tap.yml` files. Never invent
   > connectors, fields, transform types, or enum values that are not in the schema.
   > You have no shell access, so label every file you present as **not yet
   > validated** and remind me to run `tapstate validate <dir>` locally myself;
   > if I paste back a coded error, read the code and fix the file. Prefer the
   > shapes in `examples/`.

4. Save. Open a chat with the GPT and run the self-test in section 2.

> A GPT has no access to your machine, so it cannot run `tapstate validate` for
> you. That step is yours: you save the files it produces and validate them
> locally, then paste any error back. That loop *is* the test.

## 1b. Load it into an Agent-Skills-native tool (Claude Code, Cursor, Codex CLI, ...)

Copy the folder into the tool's skills location and let it discover the skill. For
Claude Code, for example:

```
cp -r authoring-skill ~/.claude/skills/tapstate-authoring
```

Then just ask it to build a Tapstate pipeline; it loads the skill on its own. These
tools can also run `tapstate validate` themselves if you let them use a shell.

---

## 2. The self-test (about 10 minutes)

Run these three conversations in order. Each says what a good answer looks like and
how to check it. Save whatever the agent produces into a directory and validate it.

### Test A — straight replication (warm-up)

Ask:

> Build a pipeline that replicates the `orders` table from MySQL into MongoDB.

A good answer produces three resources: a `kind: source` for MySQL (with
`mode: cdc`, `tables: [orders]`), a `kind: source` target for MongoDB (connection
only), and a `kind: pipeline` whose `serve.sync` points at the target. Save them and:

```
tapstate validate ./testA
# expected: valid: 3 resources in ./testA
```

Compare with `examples/01-straight-replication/`.

### Test B — a filter, and the coded-error loop

Ask:

> Only replicate orders with an amount over 100.

A good answer adds a `filter` transform with a CEL expression and points
`serve.from` at the filter step. Validate:

```
tapstate validate ./testB
```

Now deliberately exercise the repair loop: edit the file so `serve.from` names the
**source** id instead of the stream, re-validate, and you should see:

```
invalid: (batch)  dsl.missing-reference
  Reference '...' at serve.from points to nothing in this workspace.
```

Paste that error back to the agent. A good agent explains that `serve.from` needs a
stream (a step id, a table name, or `source_id.table`) and fixes it.

### Test C — nested documents (the interesting one)

Ask:

> I have `orders` and `lines` in MySQL. Write each order into MongoDB as one
> document with its line items embedded as an array.

A good answer uses a `nest` transform with the **map-form** `from`
(`{ orders: orders, lines: lines }`), a `root` anchored on `orders`, and an `embed`
with `on: { <line_field>: <order_field> }`, `as: array`, and a `path`. Validate:

```
tapstate validate ./testC
```

Compare with `examples/04-nested-documents/`.

---

## 3. What "pass" means

The skill is working for you if, across the three tests:

- every workspace you saved reaches `valid: N resources` (after at most a couple of
  fix rounds), and
- the agent **asks or states sensible defaults** rather than inventing fields, and
- when you hand it a coded error, it fixes the file instead of guessing.

If the agent invents a field or connector the validator rejects, point it back at
`REFERENCE.md` and the schema — that is the failure mode this skill exists to
prevent, and calling it out in your instructions usually settles it.

---

## 4. What's in this folder

| File | What it is |
|---|---|
| `SKILL.md` | The skill entry point: the authoring loop and the resource model. |
| `GENERATING.md` | Conversation-to-YAML playbook: elicitation checklist + validated recipes. |
| `REFERENCE.md` | Field manual for all five kinds, expressions, and canonical form. |
| `schema/tapstate-v1.schema.json` | The exact grammar — the same schema the CLI validates against. |
| `examples/<recipe>/` | Worked, validated workspaces you can copy and adapt. |
