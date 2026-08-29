package io.keydra.alerts.dto;

import io.keydra.alerts.entity.AlertMetric;
import io.keydra.alerts.entity.Comparison;
import io.keydra.alerts.entity.EventKind;
import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One rule changing its mind, in the shape it leaves in.
 *
 * <p>The same object goes to the notification hub and into the body of a webhook, which is
 * deliberate: what the application shows and what the on-call channel receives should not be two
 * descriptions of one event that can disagree.
 *
 * <p>It names the target rather than only its id. An id is enough for the application, which
 * already has the catalog; it is useless in a chat message at four in the morning.
 *
 * @param reading what was measured, or null when the measurement was that there was none
 */
@Schema(name = "AlertNotice", description = "A rule that started firing, or stopped")
public record AlertNotice(
        Long ruleId,
        String ruleName,
        Long connectionId,
        String connectionName,
        /**
         * What kind of thing this is about, or null when it is about a target.
         *
         * <p>Null for every rule, where the target's own name is the whole answer. Filled in where
         * the news is about something Keydra reaches rather than something it manages — otherwise
         * the message says a name and leaves the reader to know that {@code nightly-s3} is a backup
         * destination rather than a server.
         */
        String subject,
        EventKind kind,
        AlertMetric metric,
        Comparison comparison,
        Double reading,
        double threshold,
        Instant at) {}
