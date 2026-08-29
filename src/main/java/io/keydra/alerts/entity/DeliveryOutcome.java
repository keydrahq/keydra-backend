package io.keydra.alerts.entity;

/**
 * What became of the attempt to send an alert somewhere.
 *
 * <p>Recorded on the event rather than in a log, because "was I told?" is a question asked about a
 * particular alert, usually the one nobody saw.
 */
public enum DeliveryOutcome {
    /** No delivery was configured; the event is in the history and on the hub, and that is all. */
    NONE,
    /** Being sent. An event stuck here is one whose delivery never came back. */
    SENDING,
    SENT,
    FAILED
}
