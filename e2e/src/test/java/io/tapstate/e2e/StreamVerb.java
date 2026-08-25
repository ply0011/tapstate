package io.tapstate.e2e;

import io.tapstate.core.lifecycle.LifecycleVerb;

/**
 * The lifecycle words a step may scope to one source stream instead of to the whole pipeline.
 *
 * <p>The words themselves are the product's, taken from its verb enum rather than spelled again here,
 * so an author writes {@code pause} in both forms and neither can drift from what the product accepts.
 * What is this harness's own decision is <em>which</em> of those words may carry a source at all, and
 * that is what this enum declares: a run holds one stream by not letting its bytes through, which is a
 * thing the harness owns and the product knows nothing about.
 *
 * <p>Only the two suspending words are here. {@code start} and {@code stop} scoped to one stream would
 * be claims about a pipeline running with a member missing - a different thing entirely, and one no
 * product surface offers - so they stay whole-pipeline and the parser refuses them with a source.
 */
public enum StreamVerb {

    /** Holds the named stream: its source stops reaching the product until it is resumed. */
    PAUSE(LifecycleVerb.PAUSE),

    /** Releases a held stream, letting everything written meanwhile through in the order it was written. */
    RESUME(LifecycleVerb.RESUME);

    private final LifecycleVerb spelling;

    StreamVerb(LifecycleVerb spelling) {
        this.spelling = spelling;
    }

    /** The word an author writes, which is the product's own spelling of the same verb. */
    public String word() {
        return spelling.id();
    }
}
