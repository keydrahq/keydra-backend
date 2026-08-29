package io.keydra.alerts.rest;

import io.keydra.alerts.dto.AlertDtos.AlertEventSummary;
import io.keydra.alerts.dto.AlertDtos.AlertMetricInfo;
import io.keydra.alerts.dto.AlertDtos.AlertRuleRequest;
import io.keydra.alerts.dto.AlertDtos.AlertRuleSummary;
import io.keydra.alerts.entity.AlertMetric;
import io.keydra.alerts.service.AlertService;
import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.security.Audited;
import io.keydra.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;
import java.util.Arrays;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * Conditions somebody wants to hear about.
 *
 * <p>Everything here is about one target, so the permission is asked about that target — and
 * reading is filtered by what the caller can see rather than gated by a permission of its own, the
 * same way the catalog and the schedules are. A rule about a server somebody cannot reach is not a
 * rule they should be reading.
 */
@Path("/api/v1/alerts")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Alerts", description = "Conditions worth being told about")
@RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
public class Alerts {

    private final AlertService service;

    @Inject
    Alerts(AlertService service) {
        this.service = service;
    }

    @GET
    @Operation(
            summary = "Every rule, with where it currently stands",
            description =
                    "Filtered to the targets the caller can see. The state is held in memory and"
                            + " starts again at quiet after a restart, which each rule then"
                            + " re-establishes within its own duration.")
    @APIResponse(responseCode = "200", description = "The rules")
    public Uni<List<AlertRuleSummary>> list() {
        return service.list();
    }

    @GET
    @Path("/metrics")
    @Operation(
            summary = "The metrics a rule can watch",
            description =
                    "A closed list: it says nothing about this instance and everything about this"
                            + " build. Each one carries its unit, and whether it is a condition"
                            + " rather than a quantity — a form has no threshold to ask for when"
                            + " the answer is yes or no.")
    @APIResponse(responseCode = "200", description = "The metrics")
    public Uni<List<AlertMetricInfo>> metrics() {
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

    @GET
    @Path("/events")
    @Operation(
            summary = "What the rules have said",
            description =
                    "Newest first, and only the transitions: a rule that has been firing since"
                            + " Tuesday appears once. Filtered to the targets the caller can see,"
                            + " the same way the rules are.")
    @APIResponse(responseCode = "200", description = "The events")
    public Uni<List<AlertEventSummary>> events(@QueryParam("ruleId") Long ruleId) {
        return service.history(ruleId);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Watch for a condition",
            description =
                    "An enabled rule keeps its target sampled whether or not anybody has the"
                            + " dashboard open — which is the point of writing one.")
    @APIResponse(responseCode = "201", description = "Watching")
    @APIResponse(responseCode = "409", description = "The rule cannot work as written")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("alert.rule.create")
    @RequiresPermission(value = Permission.ALERT_MANAGE, connection = "connectionId")
    public Uni<RestResponse<AlertRuleSummary>> create(
            @QueryParam("connectionId") Long connectionId, @Valid AlertRuleRequest request) {
        return service.create(request).map(created -> RestResponse.status(Status.CREATED, created));
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Change a rule",
            description =
                    "An edited rule forgets where it stood: a threshold that has moved makes the"
                            + " old verdict meaningless, and announcing a change that never"
                            + " happened is worse than deciding again on the next reading.")
    @APIResponse(responseCode = "200", description = "Changed")
    @APIResponse(responseCode = "409", description = "The rule cannot work as written")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("alert.rule.update")
    @RequiresPermission(value = Permission.ALERT_MANAGE, connection = "connectionId")
    public Uni<AlertRuleSummary> update(
            @PathParam("id") Long id,
            @QueryParam("connectionId") Long connectionId,
            @Valid AlertRuleRequest request) {
        return service.update(id, request);
    }

    @DELETE
    @Path("/{id}")
    @Operation(summary = "Remove a rule, and the record of what it said")
    @APIResponse(responseCode = "204", description = "Removed")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("alert.rule.delete")
    @RequiresPermission(value = Permission.ALERT_MANAGE, connection = "connectionId")
    public Uni<RestResponse<Void>> delete(
            @PathParam("id") Long id, @QueryParam("connectionId") Long connectionId) {
        return service.delete(id).map(ignored -> RestResponse.noContent());
    }
}
