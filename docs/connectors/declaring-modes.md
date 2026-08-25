---
status: engineering-draft
publication: handoff
target: https://tapstate.dev/docs/connectors/declaring-modes
---

# Declaring modes

Most rows in the catalog get their modes **derived**: the connector is classloaded, the functions it
registers are read, and a connector that registers both a batch read and a stream read is recorded as
doing `snapshot` and `cdc`. For a database that is right.

For everything else it is often wrong, and confidently wrong. `csv`, `excel`, `json` and `xml` all
register a batch read and a change read, so the catalog records each of them as doing CDC on a file.
Other connectors register nothing that resolves to a mode at all and come out with an empty row; the
generated ingest report has a bucket for each shape. Nothing in a connector says "I am a file" or "I
am a queue" in a way the derivation can use.

So this repository keeps its own declaration for those connectors - the **overlay**. It is checked in
here, it outranks both derivation and whatever the upstream specification says, and it is the reason
`tapstate validate` refuses `connector: kafka` with `mode: cdc` instead of accepting it.

## Where it lives

```
core/core-catalog/src/main/resources/catalog/overlay/
  pdk/
    index.json      the ids this overlay declares
    kafka.json      one file per connector
    rabbitmq.json
    ...
```

`index.json` is a JSON array of ids. Each `<id>.json` declares modes, and optionally a sink:

```json
{
  "modes": ["stream"]
}
```

```json
{
  "modes": ["snapshot"],
  "sink": {
    "capable": true,
    "writeSemantics": ["upsert", "append"]
  }
}
```

Omitting `sink` means "derive it as usual" - not "it cannot sink".

**The directory is per connector type.** Today the only type is `pdk`, so `pdk/` is the only
directory, and it is easy to read that as decoration. It is not: the next connector type gets its own
directory beside it, not entries pushed into `pdk/`. The two would then be indistinguishable in a
catalog that has to keep them apart.

## Adding one

Adding an overlay entry is a normal pull request in this repository - no lane opens it, no upstream
revision has to move.

1. Add the id to `overlay/pdk/index.json` and write `overlay/pdk/<id>.json`.
2. Run a refresh so the shipped rows carry the declaration:
   `scripts/refresh-catalog.sh --connectors ../tapdata-connectors --spec-only`.
   Editing the overlay alone leaves the shipped rows saying the old thing, and the ordinary build
   goes red until you regenerate - see [Refreshing the catalog](refreshing-the-catalog.md).
3. Commit the overlay files and the regenerated rows together.
4. Check `tools/catalog-assembler/ingest-report.md`: if the connector's own specification declares
   something different, the divergence is listed rather than silently resolved, and if you declared a
   mode the derived capabilities contradict, that is listed too.

### What justifies a declaration

The bar is that you can answer **"on what basis is it this mode"**, because a wrong entry does not
fail - it silently admits a mode the connector cannot serve.

| Requirement | What it means |
|---|---|
| A semantic source | What the connector actually reads: an unbounded queue subscription is `stream`, a polled SaaS endpoint is `api`, a file scan is `file`. Say what the basis is - the connector implementation, an existing declaration in the upstream specification, or an observed run. "It looks like one" is not a basis |
| One human review | An overlay entry ships in the release, at the same rank as the generated rows |
| Never empty | `modes: []` is not a way to say "we declare that it supports nothing". A connector that genuinely has no usable mode does not belong in the overlay at all |

That last row is load-bearing. An empty mode set makes mode validation return early - which is
validation silently switched off for that connector, and is byte for byte what a declaration having
been wiped looks like. "We declared it" and "we declared that it does nothing" must not be the same
way of writing it. So the reader refuses rather than falling back to derivation: an empty `modes` and
a file with no `modes` key at all are each fatal and name the connector, and a missing `index.json`
is fatal too.

## What holds it up

- The ordinary build - no connector checkout, no PDK, every `mvn verify` - reconciles the shipped
  rows against this overlay: every declared id exists, the rows carry exactly the modes declared, and
  no row claims an overlay provenance the overlay does not back.
- The expected id set is pinned separately, so an overlay entry cannot quietly disappear along with
  the row that would have caught it.
- Each row records where each of its modes came from, as `derived`, `declared` (the connector's own
  specification) or `overlay` (this file). Those three stay distinct on purpose: collapse `overlay`
  into `declared` and the divergence report above has nothing left to compare.
