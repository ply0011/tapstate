# Rehearsal artifact - safe to delete

This file exists only so that a workstream rehearsing this project's execution
workflow has something to commit. It ships no behaviour: nothing builds it,
nothing imports it, and no CI gate reads it. It was placed in `.github/` rather
than `docs/` on purpose - a page under `docs/` has to declare where it is headed
for publication, and this one is headed nowhere.

Added by the `newflow-rehearsal` workstream, tracked in #73, together with an
equivalent file in one other repository. The exercise was to run the workflow's
five steps - open the execution issue, claim it, post progress, open the pull
requests, close the line - against real GitHub rather than against a test double.

## Removing it

Revert the merge commit that introduced it:

    git revert -m 1 <merge commit>

That restores the tree byte for byte. Nothing else has to be undone.
