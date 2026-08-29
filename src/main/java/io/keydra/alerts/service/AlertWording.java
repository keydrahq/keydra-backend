package io.keydra.alerts.service;

import io.keydra.alerts.dto.AlertNotice;
import io.keydra.alerts.entity.AlertMetric;
import io.keydra.alerts.entity.EventKind;
import java.util.Locale;

/**
 * One sentence a person can read without opening anything.
 *
 * <p>The part of an alert that decides whether it is any use. A message saying {@code
 * MEMORY_USED_BYTES=812443136} makes somebody go and look up what that means about which server;
 * the sentence here says which target, what was read and what the rule asked for, which is the
 * whole content of the alert.
 *
 * <p>English, and it stays English while the invitation mail no longer does. The difference is who
 * it is addressed to: an invitation goes to one account whose language Keydra knows, and an alert
 * goes at a channel — there is nobody here to look a preference up for, and a rule that fired at
 * three in the morning is read by whoever is on call.
 */
final class AlertWording {

    private AlertWording() {}

    /**
     * The subject line, which is what somebody sees before they decide to read anything.
     *
     * <p>Plain ASCII, deliberately. A dash here rather than the one the rest of this file uses:
     * anything outside ASCII makes a subject an RFC 2047 encoded word, which every mail client
     * decodes and nothing else does — and a subject is exactly the string that ends up in logs,
     * filters and phone notifications written by something that is not a mail client.
     */
    static String subject(AlertNotice notice) {
        String verb = notice.kind() == EventKind.FIRED ? "firing" : "cleared";
        return "[Keydra] " + notice.ruleName() + " - " + target(notice) + " is " + verb;
    }

    /** The whole thing in a line: what happened, where, what was read and what was asked. */
    static String sentence(AlertNotice notice) {
        if (notice.metric() == AlertMetric.NO_ANSWER) {
            return notice.kind() == EventKind.FIRED
                    ? target(notice) + " is not answering (" + notice.ruleName() + ")"
                    : target(notice) + " is answering again (" + notice.ruleName() + ")";
        }
        String reading = value(notice.metric(), notice.reading());
        String limit = value(notice.metric(), notice.threshold());
        String side = notice.comparison().name().toLowerCase(Locale.ROOT);
        return notice.kind() == EventKind.FIRED
                ? notice.ruleName()
                        + ": "
                        + target(notice)
                        + " is at "
                        + reading
                        + ", "
                        + side
                        + " "
                        + limit
                : notice.ruleName()
                        + ": "
                        + target(notice)
                        + " is back to "
                        + reading
                        + ", no longer "
                        + side
                        + " "
                        + limit;
    }

    /** The body of a message, which has room for the detail a subject line does not. */
    static String body(AlertNotice notice) {
        StringBuilder text = new StringBuilder(sentence(notice)).append("\n\n");
        text.append("Target:    ").append(target(notice)).append('\n');
        text.append("Rule:      ").append(notice.ruleName()).append('\n');
        text.append("Metric:    ").append(readable(notice.metric())).append('\n');
        if (notice.metric() != AlertMetric.NO_ANSWER) {
            text.append("Reading:   ")
                    .append(value(notice.metric(), notice.reading()))
                    .append('\n');
            text.append("Threshold: ")
                    .append(notice.comparison().name().toLowerCase(Locale.ROOT))
                    .append(' ')
                    .append(value(notice.metric(), notice.threshold()))
                    .append('\n');
        }
        text.append("At:        ").append(notice.at()).append('\n');
        return text.toString();
    }

    /**
     * What the message is about, named as specifically as the notice allows.
     *
     * <p>A rule's target is its own name and nothing else: whoever reads the channel knows what
     * {@code payments-cache} is. News about something Keydra reaches rather than manages says what
     * kind of thing it is first, because {@code nightly-s3} on its own could be anything.
     */
    private static String target(AlertNotice notice) {
        if (notice.connectionName() == null) {
            return "connection " + notice.connectionId();
        }
        return notice.subject() == null
                ? notice.connectionName()
                : notice.subject() + " " + notice.connectionName();
    }

    /** A metric written as words rather than as a constant. */
    private static String readable(AlertMetric metric) {
        return metric.name().toLowerCase(Locale.ROOT).replace('_', ' ');
    }

    /**
     * A number with its unit, at the scale a person reads it in.
     *
     * <p>Bytes become the largest unit that leaves a number under four digits, because "812 MB" is
     * read at a glance and "851443712" is counted with a finger.
     */
    static String value(AlertMetric metric, Double reading) {
        if (reading == null) {
            return "no reading";
        }
        return switch (metric.unit()) {
            case BYTES -> bytes(reading);
            case PERCENT -> round(reading) + "%";
            case SECONDS -> round(reading) + "s";
            case PER_SECOND -> round(reading) + "/s";
            case PER_MINUTE -> round(reading) + "/min";
            case COUNT -> round(reading);
            case CONDITION -> reading > 0 ? "yes" : "no";
        };
    }

    private static String bytes(double value) {
        String[] units = {"B", "KB", "MB", "GB", "TB"};
        double scaled = value;
        int unit = 0;
        while (scaled >= 1024 && unit < units.length - 1) {
            scaled /= 1024;
            unit++;
        }
        return round(scaled) + " " + units[unit];
    }

    private static String round(double value) {
        return value == Math.rint(value) && !Double.isInfinite(value)
                ? String.valueOf((long) value)
                : String.format(Locale.ROOT, "%.1f", value);
    }
}
