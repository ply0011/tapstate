package io.tapstate.control.core;

/**
 * The follows currently running against each source, as the delete path sees them.
 *
 * <p>It exists because a follow's lifetime and its source's are unrelated, and nothing else notices.
 * A source that exists only to be read is a first-class thing now — nothing has to reference it — so
 * the two refusals that guard a delete both pass for one: they read the artifact graph, and a
 * watcher is not in the graph. Deleted, the artifact is gone and the stream keeps delivering rows
 * from a source the user has been told no longer exists.
 *
 * <p>Implemented by whichever face holds the live streams. The control ring names the capability and
 * not the holder, so a control plane assembled without a streaming face still has something to call.
 */
public interface DataBrowserFollows {

    /**
     * Stops every follow running against {@code sourceId}. Called after a delete has actually
     * happened, never before: a delete that was refused must leave its streams alone, and the
     * refusals are what decide.
     */
    void closeFollowsOf(String sourceId);

    /** For a control plane assembled with no streaming face — there are no follows to stop. */
    DataBrowserFollows NONE = sourceId -> {
    };
}
