---
status: engineering-draft
publication: handoff
target: https://tapstate.dev/docs/tutorials
---

# Tutorials

Worked scenarios you can run end to end. Each one builds something real and says what to look at
afterwards. Most load their own sample data and assume the stack from the
[online quickstart](../quickstart-online.md) is up; the one that stands the processes up from an IDE
instead says so, and is where to start if you are working on Tapstate itself rather than using it.

| Tutorial | What you build | Time |
|---|---|---|
| [Assembling one document out of many tables](nest-document-assembly/) | Three live documents - order, customer and product - assembled from the same nine relational tables with a `nest` transform and kept current by change data capture. Includes the rule that decides which document shapes a nest can express. | ~30 min |
| [Running the server and CLI from an IDE](running-from-an-ide/) | The server out of IntelliJ IDEA against a MongoDB you supply, and the CLI driving it from a terminal, ending at a connected and authenticated CLI. Carries no sample data: it stops before anything moves, and hands over to the tutorial above. | ~20 min |

## Adding a tutorial

**One directory per tutorial, and everything it needs lives inside it.**

```
docs/tutorials/
├── README.md                     <- this index
└── <tutorial-slug>/
    ├── README.md                 <- the walkthrough itself
    ├── <data>.sql                <- sample data, if it has any
    └── <other assets>            <- workspace files, diagrams, scripts
```

The directory name is the slug a reader sees in the URL, so name it for the thing being taught
(`nest-document-assembly`), not for the page (`tutorial-3`). Putting the walkthrough in `README.md`
means a link to the directory renders it.

**Classify the page.** A new walkthrough is an engineering draft until documentation
engineering publishes it, so its `README.md` opens with the draft header and names where it
is headed:

```
---
status: engineering-draft
publication: handoff
target: https://tapstate.dev/docs/tutorials/<tutorial-slug>
---
```

Once the published page is canonical, the page here shrinks to a pointer carrying
`status: canonical-pointer` and its `canonical_url`, and the sample data stays. The sample
data itself is never classified - it is executable, not a page. A pull request that adds or
changes a page without one of the two headers is refused; the full contract is in
[CONTRIBUTING.md](../../CONTRIBUTING.md#documentation).

Two rules the existing one follows and the next one should too:

- **Sample data is generated deterministically** - no random values, no timestamps of the day it ran.
  A tutorial quotes row counts and document shapes in its text, and those numbers have to be the ones
  the reader sees. Seed with a numbers table and arithmetic, not with `RAND()`.
- **Say what does not work, not only what does.** The failure a reader would otherwise ship is worth
  more space than the happy path, especially where the product accepts something and goes wrong
  quietly rather than refusing it.
