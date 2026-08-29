package io.keydra.alerts.dto;

import io.keydra.alerts.entity.AlertBasis;
import io.keydra.alerts.entity.AlertMetric;
import io.keydra.alerts.entity.AlertState;
import io.keydra.alerts.entity.Comparison;
import io.keydra.alerts.entity.DeliveryKind;
import io.keydra.alerts.entity.DeliveryOutcome;
import io.keydra.alerts.entity.EventKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What the alert endpoints take and return.
 *
 * <p>Records in one place, like the other domains: they are the wire shape and nothing else, and a
 * file each would be nine files that only ever change together.
 */
public final class AlertDtos {

    private AlertDtos() {}

    /**
     * A rule as somebody writes it.
     *
     * <p>The optional fields are optional because an edit that leaves one out means "leave it as it
     * was" rather than "set it to nothing" — the same rule the rest of the API follows.
     */
    @Schema(name = "AlertRuleRequest", description = "A condition to watch for")
    public record AlertRuleRequest(
            @NotBlank String name,
            @NotNull Long connectionId,
            @NotNull AlertMetric metric,
            Comparison comparison,
            AlertBasis basis,
            Double threshold,
            Integer baselineWindowSeconds,
            Integer baselineOffsetSeconds,
            Integer forSeconds,
            Boolean enabled,
            List<Long> deliveryIds) {}

    /**
     * A rule with everything needed to draw a row for it.
     *
     * @param state where it stands right now, which is held in memory and starts again at OK after
     *     a restart
     * @param reading the last reading taken for its metric, or null when the target could not
     *     answer it
     * @param watching whether the target is actually being sampled, so a rule that cannot see
     *     anything says so instead of looking quiet
     * @param baseline what the metric read over the window this rule compares against, or null for
     *     an absolute rule and for one whose window cannot be answered yet — which is a thing an
     *     interface has to be able to say out loud rather than draw as a zero
     */
    @Schema(name = "AlertRuleSummary", description = "A rule and where it currently stands")
    public record AlertRuleSummary(
            Long id,
            String name,
            Long connectionId,
            String connectionName,
            AlertMetric metric,
            AlertMetric.Unit unit,
            Comparison comparison,
            AlertBasis basis,
            double threshold,
            int baselineWindowSeconds,
            int baselineOffsetSeconds,
            Double baseline,
            int forSeconds,
            boolean enabled,
            /** Every place this rule announces itself; empty is the notification hub alone. */
            List<Long> deliveryIds,
            /** Their names, in the same order, for a page that has no room for ids. */
            List<String> deliveryNames,
            String createdBy,
            Instant createdAt,
            AlertState state,
            Instant since,
            Double reading,
            Instant readAt,
            boolean watching) {}

    /**
     * One metric a rule can be written about.
     *
     * @param condition true for a metric that is a yes or a no, so a form knows not to ask for a
     *     threshold nobody would mean anything by
     */
    @Schema(name = "AlertMetricInfo", description = "A metric a rule can watch")
    public record AlertMetricInfo(String name, AlertMetric.Unit unit, boolean condition) {}

    /** Something a rule said, after the fact. */
    @Schema(name = "AlertEventSummary", description = "A rule that started firing, or stopped")
    public record AlertEventSummary(
            Long id,
            Long ruleId,
            String ruleName,
            Long connectionId,
            String connectionName,
            EventKind kind,
            AlertMetric metric,
            Double reading,
            double threshold,
            Instant at,
            String deliveryName,
            DeliveryOutcome deliveryOutcome,
            String deliveryDetail) {}

    /**
     * Somewhere to send alerts, as somebody writes it.
     *
     * <p>The secrets follow the rule every other secret in this API follows: absent means keep what
     * is stored, empty means clear it, and a stored one is never sent back.
     */
    @Schema(name = "AlertDeliveryRequest", description = "Somewhere to send alerts")
    public record AlertDeliveryRequest(
            @NotBlank String name,
            @NotNull DeliveryKind kind,
            Boolean enabled,
            String url,
            String headerName,
            String headerValue,
            String smtpHost,
            Integer smtpPort,
            Boolean smtpTls,
            String username,
            String password,
            String fromAddress,
            String toAddresses,
            String apiToken,
            String recipient,
            String senderId) {}

    /**
     * A delivery as the API describes it.
     *
     * <p>No address here, only its host. A webhook URL is not a location, it is a credential: the
     * token is in the path, and anybody holding the string can post as this application. It is
     * stored encrypted and reported the way a password is — whether one exists, and nothing more.
     *
     * @param hasUrl whether an address is stored, never what it is
     * @param hasSecret whether a header value is stored, never what it is
     * @param hasPassword whether a mail password is stored, never what it is
     * @param hasApiToken whether a chat tool's token is stored, never what it is
     * @param recipient the chat, channel or number a message goes to — not a secret in any of the
     *     three, so it is shown
     * @param senderId the WhatsApp number id a message is sent from
     * @param describedAs where this points, in one line, so a list answers "which one is the
     *     on-call channel" without opening anything
     */
    @Schema(name = "AlertDeliverySummary", description = "Somewhere alerts are sent")
    public record AlertDeliverySummary(
            Long id,
            String name,
            DeliveryKind kind,
            boolean enabled,
            String urlHost,
            boolean hasUrl,
            String headerName,
            boolean hasSecret,
            String smtpHost,
            Integer smtpPort,
            boolean smtpTls,
            String username,
            boolean hasPassword,
            String fromAddress,
            String toAddresses,
            boolean hasApiToken,
            String recipient,
            String senderId,
            String describedAs,
            int usedByRules) {}

    /** What happened when somebody asked whether a delivery works. */
    @Schema(name = "AlertDeliveryCheck", description = "The result of sending a test message")
    public record AlertDeliveryCheck(boolean working, String detail) {}
}
