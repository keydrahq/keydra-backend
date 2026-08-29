package io.keydra.cluster.dto;

/**
 * This instance took on the work that must happen once, or gave it up.
 *
 * <p>An event rather than a call, so the beans that care — the ones holding a watch on a target
 * because a rule asked for it — hear about a handover without the lease knowing they exist.
 *
 * @param role which lease changed hands
 * @param leader whether this instance now holds it
 * @param instanceId which instance this is, for a log line that means something in a pile of them
 */
public record LeadershipChanged(String role, boolean leader, String instanceId) {}
