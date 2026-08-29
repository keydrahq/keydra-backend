package io.keydra.alerts.entity;

/**
 * What happened to a rule.
 *
 * <p>Only two, because only transitions are worth recording. A rule that keeps firing is not news
 * every five seconds, and a history full of the same line is a history nobody scrolls.
 */
public enum EventKind {
    /** The condition held for as long as the rule asked. */
    FIRED,
    /** It stopped holding, which is the line that lets somebody go back to sleep. */
    CLEARED
}
