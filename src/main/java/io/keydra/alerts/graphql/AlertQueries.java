package io.keydra.alerts.graphql;

import io.keydra.alerts.dto.AlertDtos.AlertDeliveryCheck;
import io.keydra.alerts.dto.AlertDtos.AlertDeliveryRequest;
import io.keydra.alerts.dto.AlertDtos.AlertDeliverySummary;
import io.keydra.alerts.dto.AlertDtos.AlertEventSummary;
import io.keydra.alerts.dto.AlertDtos.AlertMetricInfo;
import io.keydra.alerts.dto.AlertDtos.AlertRuleRequest;
import io.keydra.alerts.dto.AlertDtos.AlertRuleSummary;
import io.keydra.alerts.entity.AlertMetric;
import io.keydra.alerts.service.AlertDeliveryService;
import io.keydra.alerts.service.AlertService;
import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.common.graphql.OneAtATime;
import io.keydra.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

/**
 * Rules that watch, and what they have said.
 *
 * <p>The history is the third long list: a fired-and-cleared pair per rule per incident, kept
 * indefinitely, and a page showing when-and-what was being sent the reading, the threshold, the
 * comparison and the delivery outcome of every one.
 *
 * <p>Transport only, over the service the resource already uses: a rule is about a target, so the
 * rules and their history show only the ones whose target the caller can see.
 */
@GraphQLApi
@OneAtATime
public class AlertQueries {

    private final AlertService service;
    private final AlertDeliveryService deliveries;

    @Inject
    AlertQueries(AlertService service, AlertDeliveryService deliveries) {
        this.service = service;
        this.deliveries = deliveries;
    }

    @Query("alertRules")
    @Description("The rules, with what each one is reading now and which state it is in")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    public Uni<List<AlertRuleSummary>> alertRules() {
        return service.list();
    }

    @Query("alertEvents")
    @Description("What has fired and what has cleared, newest first")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    public Uni<List<AlertEventSummary>> alertEvents(
            @Name("ruleId") @Description("One rule's history, or every rule's") Long ruleId) {
        return service.history(ruleId);
    }

    /**
     * The metrics a rule can watch.
     *
     * <p>A plain list rather than a connection: it is an enum, so it says nothing about this
     * instance and everything about this build. Paging a fixed set is machinery nobody needs.
     */
    @Query("alertMetrics")
    @Description("The metrics a rule can watch, each with its unit")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    public Uni<List<AlertMetricInfo>> alertMetrics() {
        return Uni.createFrom()
                .item(
                        Arrays.stream(AlertMetric.values())
                                .map(
                                        metric ->
                                                new AlertMetricInfo(
                                                        metric.name(),
                                                        metric.unit(),
                                                        metric.isCondition()))
                                .toList());
    }

    /**
     * Where a firing rule sends word.
     *
     * <p>Guarded more tightly than the rules are, and that is deliberate rather than inherited: a
     * delivery holds a bot token or a webhook URL, which is a credential. Reading the list of them
     * is an administrator's business even where reading the rules is not.
     */
    @Query("alertDeliveries")
    @Description("The channels a firing rule can send word through")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.ALERT_DELIVERY_MANAGE)
    public Uni<List<AlertDeliverySummary>> alertDeliveries() {
        return deliveries.list();
    }

    // --- Changing them ------------------------------------------------------

    @Mutation("createAlertRule")
    @Description("Adds a rule watching one metric on one target")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.ALERT_MANAGE, connection = "connectionId")
    public Uni<AlertRuleSummary> createAlertRule(
            @Name("connectionId") @Description("The target the rule watches") Long connectionId,
            @Name("rule") @Valid AlertRuleRequest rule) {
        return service.create(rule);
    }

    @Mutation("updateAlertRule")
    @Description("Changes a rule")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.ALERT_MANAGE, connection = "connectionId")
    public Uni<AlertRuleSummary> updateAlertRule(
            @Name("id") Long id,
            @Name("connectionId") @Description("The target the rule watches") Long connectionId,
            @Name("rule") @Valid AlertRuleRequest rule) {
        return service.update(id, rule);
    }

    /** Answers whether there was one to remove; deleting something twice is not an error. */
    @Mutation("deleteAlertRule")
    @Description("Removes a rule")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.ALERT_MANAGE, connection = "connectionId")
    public Uni<Boolean> deleteAlertRule(
            @Name("id") Long id,
            @Name("connectionId") @Description("The target the rule watches") Long connectionId) {
        return service.delete(id);
    }

    @Mutation("createAlertDelivery")
    @Description("Adds a channel for firing rules to send word through")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.ALERT_DELIVERY_MANAGE)
    public Uni<AlertDeliverySummary> createAlertDelivery(
            @Name("delivery") @Valid AlertDeliveryRequest delivery) {
        return deliveries.create(delivery);
    }

    @Mutation("updateAlertDelivery")
    @Description("Changes a channel")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.ALERT_DELIVERY_MANAGE)
    public Uni<AlertDeliverySummary> updateAlertDelivery(
            @Name("id") Long id, @Name("delivery") @Valid AlertDeliveryRequest delivery) {
        return deliveries.update(id, delivery);
    }

    @Mutation("deleteAlertDelivery")
    @Description("Removes a channel")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.ALERT_DELIVERY_MANAGE)
    public Uni<Boolean> deleteAlertDelivery(@Name("id") Long id) {
        return deliveries.delete(id);
    }

    /**
     * Sends a test message through a channel.
     *
     * <p>A mutation rather than a query, and not because it writes: it leaves the building. A query
     * is expected to be safe to run twice and safe to cache, and a message somebody receives is
     * neither.
     */
    @Mutation("checkAlertDelivery")
    @Description("Sends a test message through a channel and reports what happened")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.ALERT_DELIVERY_MANAGE)
    public Uni<AlertDeliveryCheck> checkAlertDelivery(@Name("id") Long id) {
        return deliveries.check(id);
    }
}
