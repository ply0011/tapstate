package io.tapstate.e2e;

/**
 * How a run holds one store's traffic, seen from the binding that asks for it.
 *
 * <p>A seam rather than a method on the binding because the hold belongs to whoever brought the stores
 * up: it sits in front of a store, not in front of the product, and the binding only knows which source
 * a step named.
 */
interface StreamHolds {

    /** Holds or releases whichever store the given source's address lands on. */
    void drive(EndpointAddress address, StreamVerb verb);

    /**
     * For a run that brought up no stores of its own, and therefore has nothing to hold.
     *
     * <p>It refuses rather than doing nothing. A specification that holds a stream, is obeyed by nobody,
     * and goes green would be asserting the opposite of what it says - the arrival order it set out to
     * disagree with is the one it would then be measuring.
     */
    static StreamHolds none() {
        return (address, verb) -> {
            throw new EnvelopeException(
                    "this run brought up no store it can hold traffic for, so " + verb.word()
                            + " cannot be scoped to " + address.resourceId()
                            + "; scoped holds need stores the run provisioned itself");
        };
    }
}
