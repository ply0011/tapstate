package io.tapstate.tools.catalog.assembler;

import java.util.List;

import io.tapstate.core.catalog.OfficialConnectors;

/**
 * Decides whether an upstream drift opens a pull request now or waits for company.
 *
 * <p>Every drift is eventually carried into the catalog; the only question here is what gets its own
 * pull request. A scan that opened one per drift would interrupt roughly every four days, most times
 * over a connector this release does not support, and a review nobody has time for is a review that
 * stops happening. So a drift touching a supported connector opens immediately, and everything else
 * accumulates until the next pull request — whichever opens it — sweeps it up.
 *
 * <p>The wait has a ceiling, because the trigger can go quiet for a long time: which of the two
 * halves actually drives this depends on how many connectors the release supports, and that set
 * grows. While it is small, the ceiling is what opens nearly every pull request and the scan is in
 * effect a timer; once the set is large the trigger takes over and the ceiling is the safety net it
 * reads like. Both are the intended behaviour — a scan that has not opened anything in a while is not
 * evidence it is broken.
 */
final class DriftTriage {

    /** What the scan does with what it found. */
    enum Decision {
        /** Open a pull request carrying every drift seen so far. */
        OPEN,
        /** Carry on accumulating; some later run will take these along. */
        HOLD,
        /** Upstream matches the catalog — there is nothing to put in a pull request. */
        NOTHING
    }

    /**
     * How long held drift may wait. Deliberately a fixed number rather than "long enough": the point
     * is that an unsupported connector's specification cannot go stale indefinitely just because no
     * supported one moved.
     */
    static final int FALLBACK_DAYS = 7;

    private DriftTriage() {
    }

    static Decision decide(List<String> changedConnectorIds, int daysSinceLastPullRequest) {
        if (changedConnectorIds.isEmpty()) {
            // Checked before the ceiling: the ceiling exists to flush held drift, and with nothing
            // held there is nothing to flush - an empty pull request every seventh day would train
            // its reviewers to close this one unread.
            return Decision.NOTHING;
        }
        if (changedConnectorIds.stream().anyMatch(OfficialConnectors::isOfficial)) {
            return Decision.OPEN;
        }
        return daysSinceLastPullRequest >= FALLBACK_DAYS ? Decision.OPEN : Decision.HOLD;
    }
}
