# The demo, shot by shot

This is the recording's script and its audit trail. One row per shot: what is typed, what a viewer
sees, and which claim that shot is evidence for. It is public on purpose — "is this demo edited?" is a
fair question to ask of any product video, and the answer should be something you can check line by
line rather than a promise.

Every shot is a command run against the stack the [quickstart](quickstart-online.md) brings up. Nothing
is spliced, nothing is sped up, and the terminal is never cleared between shots except where a row says
so.

## Before the camera

**The images are pulled first, and the recording says so.** The stack pulls four images —
`mongo:7.0`, `mysql:8.0`, `postgres:16`, and the tapstate server image. On a machine that has never
seen them that is minutes, not seconds, and a 90-second recording that quietly skipped it would be
making a claim about how long this takes that nobody could reproduce. So: pull them before recording,
and say it here and beside the recording. **Your first run includes a download the recording does
not.**

Fixed before the first take, because changing any of them afterwards means recording again:

| Setting | Value | Why this one |
|---|---|---|
| terminal size | 100 × 30 | Wide enough for the assembled document to print without wrapping; small enough that the GIF stays under the README's budget |
| font size | 16px | Legible in a README at half scale |
| frame rate | 12 fps | A terminal recording is mostly still frames; 12 is smooth for typing and a third the size of 30 |
| total length | under 90 seconds | The attention budget of the audience this is for |

**The first frame carries the version.** The prompt shows the release tag, the short commit SHA and
the date, so the recording proves on screen which build it is of. Without it, "the recording matches
the release" is two humans writing the same version number in two places and hoping — and a recording
made against the wrong build looks completely normal.

## The shots

Timestamps are filled in from the finished recording; they are also what the README's index links to.

| # | At | Typed | On screen | Evidence for |
|---|---|---|---|---|
| 1 | 00:00 | `curl -sSL https://install.tapstate.dev \| sh` | The stack comes up: two source engines, the server, and the managed store. Ends with a line naming the orders assembled and the shipments inside them | Milestone criterion 2 — a clean machine reaches a reproducible cross-source demo from one command |
| 2 | 00:12 | `tapstate -w work` then `connect` / `login` | A prompt. **No database URI is typed, here or anywhere later** | Criterion 3 — the CLI reads live state without being handed a store address |
| 3 | 00:18 | `show collections` | One line: `views.order_state`. Nothing else — no intermediate resource, no staging collection | Criterion 3, positive half (the negative half is the query plan's own case) |
| 4 | 00:24 | `views.order_state.find({id:1})` | One order with its shipments **inside it** — a document assembled from two different database engines | Criterion 2 — the assembly, not two syncs standing side by side |
| 5 | 00:34 | `watch views.order_state.find({id:1})` | The same object, held on screen and redrawn in place | Criterion 5 — the live view starts and holds |
| 6 | 00:40 | *(second pane)* `INSERT INTO shipments …` in PostgreSQL | The watched object gains an array element **while you are looking at it** | Criteria 2 and 5 — a change in one engine ripples into the object rooted in the other |
| 7 | 00:48 | *(second pane)* `UPDATE orders SET customer=… ` in MySQL | The same object's own column flips, and the array stays where it is | Criterion 2 — both directions ripple, and neither rebuild drops the other's half |
| 8 | 00:56 | `pause` the fulfillment stream, write two rows, `resume` | The object does not move while the stream is held, then takes both rows at once when it is let go — and is correct afterwards | Criterion 9 — **see the note below on what this does and does not prove** |
| 9 | 01:12 | An MCP client asking "current state of order 1?" | The agent answers out of the same materialized object, through `data_browser_collections` / `data_browser_find` | Criterion 6 — the state-query MCP reaches the same object |
| 10 | 01:20 | — | Closing frame: what this was, and the one-line install again | — |

## Which shots a test is watching, and which are only watched here

Most of these shots have a case behind them that fails if the product stops doing what the shot
shows. Three do not, and saying which is the point of writing this down:

| Shot | Watched by |
|---|---|
| 1, 3, 4, 6, 7 | The golden-path end-to-end case, which runs the same pipeline file this demo generates |
| 8 | The held-stream end-to-end case, on the terms in the next section |
| 2, 5 | **Nothing automated.** A test can assert that `watch` sent the right escape sequences; only a person can see whether the screen redraws in place. This is what the [browsing live data by hand](tutorials/browsing-live-data-by-hand/) walkthrough is for |
| 9 | **Partly.** The MCP read tools, and their answers following newly applied sources without restarting the session, are covered. That the agent reaches *this demo's* assembled object is shown here and nowhere else |
| 10 | Nothing. It is a closing frame |

## What shot 8 proves, and what it does not

It proves two things: **only the named stream stops** — the other one keeps moving on screen, which is
the whole reason a per-stream hold exists — and **nothing written during the hold is lost**: the rows
were already in PostgreSQL and could not cross, and letting go delivers them onto the order they
belong to.

It does **not** prove that the result is ordered by source position rather than by arrival. That is a
real property of the design, and it is not one this demo can demonstrate: within a stream, arrival
order and source order are the same by construction, and across streams the two are never compared. A
recording that claimed otherwise would be claiming evidence it does not have. The end-to-end case
behind this shot says the same thing, in the same words, at the top of its file.

## How it is published

Two carriers, because they are for different readers:

- **A GIF (or animated SVG) in the README's first screen**, generated from the recording. This is the
  one a visitor sees without clicking anything, so it carries the whole story and the timestamp index
  below it.
- **The `.cast` on asciinema.org**, linked underneath. That is the copyable one: a reader can select
  the commands out of it and paste them, which no video allows.

The README's index under the recording points at the shots above: `00:24 the assembled object` /
`00:40 a change in either engine ripples` / `00:56 holding one stream`.
