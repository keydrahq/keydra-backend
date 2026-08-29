package io.keydra.alerts.entity;

/**
 * Where a rule currently stands.
 *
 * <p>Three rather than two, and the middle one is the point. A rule that fires the instant a
 * reading crosses its threshold fires on every scrape spike, and an alert that has cried wolf twice
 * gets muted — which is worse than never having had one. {@link #PENDING} is the condition holding
 * but not yet for long enough to be worth anybody's night.
 *
 * <p>Kept in memory rather than on the row. The state of a rule is a fact about the last few
 * minutes, not about the configuration, and writing it back every five seconds would be a database
 * write per rule per reading for something the next reading replaces. A restart forgets what was
 * firing and learns it again within the rule's own duration, which is the same thing it would do if
 * the reading had only just started.
 */
public enum AlertState {
    /** The condition does not hold. */
    OK,
    /** It holds, but not yet for as long as the rule asks. */
    PENDING,
    /** It has held for long enough, and somebody has been told. */
    FIRING
}
