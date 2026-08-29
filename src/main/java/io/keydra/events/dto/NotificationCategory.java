package io.keydra.events.dto;

/**
 * Categories carried by {@link Notification#category()}.
 *
 * <p>Kept as constants rather than an enum because the wire format is a plain string and the
 * frontend subscribes by that string.
 */
public final class NotificationCategory {

    private NotificationCategory() {}

    public static final String CONNECTION_CREATED = "ConnectionCreated";
    public static final String CONNECTION_UPDATED = "ConnectionUpdated";
    public static final String CONNECTION_DELETED = "ConnectionDeleted";
    public static final String CONNECTION_STATUS_CHANGED = "ConnectionStatusChanged";
    public static final String KEYS_CHANGED = "KeysChanged";
    public static final String VALUE_CHANGED = "ValueChanged";

    /** A message that arrived on a subscribed channel. */
    public static final String CHANNEL_MESSAGE = "ChannelMessage";

    /** A subscription opened, closed, or dropped because its connection did. */
    public static final String SUBSCRIPTION_CHANGED = "SubscriptionChanged";

    /** A fresh reading of a watched target's vital signs. */
    public static final String METRICS_SAMPLE = "MetricsSample";

    /** Sampling started or stopped for a target. */
    public static final String MONITORING_CHANGED = "MonitoringChanged";

    /**
     * How far a migration between two targets has got.
     *
     * <p>Broadcast rather than answered on the request that started it: moving a large keyspace
     * takes minutes, and a page reloaded halfway through would otherwise lose sight of a job that
     * is still running.
     */
    public static final String MIGRATION_PROGRESS = "MigrationProgress";

    /**
     * A scheduled job that did not do what it was arranged to do.
     *
     * <p>Only the failures, and it stays that way: this is the one a person is shown. A toast per
     * successful run every five minutes would bury the one that matters.
     */
    public static final String SCHEDULE_FAILED = "ScheduleFailed";

    /**
     * A scheduled job finished, whatever it did.
     *
     * <p>Beside {@link #SCHEDULE_FAILED} rather than instead of it, because the two are for
     * different audiences. That one is shown to a person. This one is for a page that is drawing a
     * "last run" column: it has to change when a run succeeds as much as when it fails, and without
     * it the only way to notice was to ask again every fifteen seconds — a poll on every open tab,
     * for a table that changes a few times an hour.
     *
     * <p>Carries nothing but the job's id. What a table wants after this is the rows as they are
     * now, not a fragment to patch in.
     */
    public static final String SCHEDULE_RAN = "ScheduleRan";

    /**
     * A rule started firing, or stopped.
     *
     * <p>Both, unlike the schedules, because the second one is the line that lets somebody stop
     * worrying — and only the transitions are broadcast, so a condition that has held all night is
     * one message and not seventeen thousand.
     */
    public static final String ALERT_CHANGED = "AlertChanged";

    /**
     * A session has ended, and whoever is on it should know rather than find out.
     *
     * <p>Named by session rather than by person. A browser knows which session it is and acts;
     * everything else on the socket learns only that some session somewhere ended, which is nothing
     * — and the alternative, naming the account, would tell every open page in the building who had
     * just been signed out.
     *
     * <p>Sent before the socket is closed, because a socket that simply drops looks like a network
     * problem, and somebody who thinks it is a network problem waits instead of signing in again.
     */
    public static final String SESSION_ENDED = "SessionEnded";

    /**
     * A sign-in that worked but did not look like the ones before it.
     *
     * <p>Not a refusal and not an error — the password was right. What it carries is the shape
     * around a correct password, which is the only thing left to look at once somebody has one.
     */
    public static final String SIGN_IN_FLAGGED = "SignInFlagged";

    /**
     * How far a purge has got.
     *
     * <p>Clearing a namespace walks the keyspace and deletes as it goes, which on a large target is
     * a minute of a dialog saying nothing. The same reasoning as a migration's progress: the work
     * is long enough to watch, so it says where it is rather than only what it did.
     */
    public static final String PURGE_PROGRESS = "PurgeProgress";

    /**
     * Somebody has asked for an operation that needs a second person.
     *
     * <p>The one a person is shown, and it carries nothing but the request's id and the target it
     * is about — the same reasoning as {@link #SCHEDULE_RAN}. What a page wants after this is the
     * rows as they are now, and what it must not be handed is a glob or a key name: the audience
     * for a broadcast is everybody who can see the target, which is wider than the set of people
     * who could answer this.
     */
    public static final String APPROVAL_REQUESTED = "ApprovalRequested";

    /**
     * A request was answered, withdrawn, expired, or finished running.
     *
     * <p>Beside {@link #APPROVAL_REQUESTED} rather than instead of it, for the reason {@link
     * #SCHEDULE_RAN} sits beside {@link #SCHEDULE_FAILED}: that one is shown to a person and this
     * one is for a page redrawing a table. Every ending, including the ones nobody pressed, because
     * the person waiting for an answer is the one who most needs to see it arrive.
     */
    public static final String APPROVAL_CHANGED = "ApprovalChanged";
}
