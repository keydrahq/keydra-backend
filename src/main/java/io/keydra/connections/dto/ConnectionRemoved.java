package io.keydra.connections.dto;

/**
 * A profile that no longer exists.
 *
 * <p>Fired so the things holding something for a target can let go of it without the connections
 * domain having to know what they are. The alternative was the deletion calling each of them by
 * name, which is how a delete method ends up importing the monitor, the sampler and whatever comes
 * next.
 *
 * <p>What was leaking without it: a target being sampled kept its timer after the profile was
 * deleted, so the instance went on asking a server it no longer knew about for its statistics,
 * every five seconds, until it was restarted.
 */
public record ConnectionRemoved(Long id) {}
