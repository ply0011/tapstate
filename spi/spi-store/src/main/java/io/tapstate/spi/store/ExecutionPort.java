package io.tapstate.spi.store;

/**
 * Marks a port that <em>drives a connector</em> rather than storing or retrieving a document. The
 * ports ring holds both kinds side by side: the storage ports the control layer is meant to depend
 * on, and these execution ports, which run where the connectors run.
 *
 * <p>The distinction is load-bearing, not descriptive. Control reaches an execution port only
 * through a whitelisted runtime probe — a closed set of synchronous channels. Compiling against an
 * execution port from the control layer would be legal to the coarse package rule (the whole ports
 * package is granted to control for its storage ports) yet would bypass that seam entirely and run
 * the connector on the wrong side of the split. This marker is what lets the architecture gate name
 * the execution ports as a set instead of listing them one by one: a port that forgets to carry it
 * silently drops out of the gate's coverage, so every new execution port implements this.
 *
 * <p>Deliberately empty: it classifies, it does not add behaviour.
 */
public interface ExecutionPort {}
