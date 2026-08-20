package io.tapstate.cli;

import java.util.Map;

/**
 * What one poll of the in-place view came back with. Three outcomes, and the view answers each
 * differently, which is why they are three cases rather than a nullable row: a row that is no longer
 * there and a frame the view could not take look identical as "no row to draw", and they mean opposite
 * things to a reader.
 */
sealed interface WatchPoll {

    /**
     * The row the view is watching, as it is now.
     *
     * @param approximateTotal how many rows the collection holds, or null when the read carried a
     *                         filter and the count was therefore not paid for
     */
    record Row(Map<String, Object> row, Long approximateTotal) implements WatchPoll {
    }

    /** The read succeeded and matched nothing — what the view was watching is not there any more. */
    record NoRow() implements WatchPoll {
    }

    /**
     * The view did not get to look this time, and why. A repeated background read that died because it
     * could not take its turn would be a far worse answer than missing a frame, so a skip is a normal
     * outcome here rather than a failure: the interactive reads it stepped aside for are the ones a
     * person is waiting on.
     */
    record Skipped(String reason) implements WatchPoll {
    }
}
