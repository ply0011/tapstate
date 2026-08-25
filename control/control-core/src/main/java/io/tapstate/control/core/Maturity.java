package io.tapstate.control.core;

/**
 * Per-frontend rollout stage at which an operation becomes visible on a face.
 *
 * <p>Declaration order is the rollout order {@code POC < ALPHA < BETA < GA}, so a face surface can be
 * derived as "every operation whose stage on this face is at or below the stage this build ships at"
 * (see {@link OperationRegistry#exposedOn(Frontend)} and {@link #CURRENT}).
 */
public enum Maturity {
    POC,
    ALPHA,
    BETA,
    GA;

    /**
     * The stage this build ships at, and therefore the ceiling every face surface is clipped with (see
     * {@link OperationRegistry#exposedOn(Frontend)}). Held in one place so that no face can be derived
     * against a stage of its own choosing.
     *
     * <p>Nothing verifies this value against the actual release stage — that judgement is a human act at
     * release time. What the single source of truth removes is the other failure: a face quietly opening a
     * wider surface than the rest because it named a ceiling nobody reconciled.
     */
    public static final Maturity CURRENT = ALPHA;
}
