package io.keydra.monitoring.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.engine.ClientConnection;
import io.keydra.engine.MetricsSample;
import io.keydra.engine.SlowCommand;
import io.keydra.monitoring.dto.BigKeysReport;
import io.keydra.monitoring.dto.MetricsHistory;
import io.keydra.monitoring.dto.MonitoringState;
import io.keydra.monitoring.service.MetricsHistoryService;
import io.keydra.monitoring.service.MetricsSampler;
import io.keydra.security.Audited;
import io.keydra.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** What a target is doing, and what it is holding. */
@Path("/api/v1/connections/{connectionId}/monitoring")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Monitoring", description = "Server statistics, slow commands, clients and big keys")
@RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
public class Monitoring {

    private final MetricsSampler sampler;
    private final MetricsHistoryService history;

    @Inject
    Monitoring(MetricsSampler sampler, MetricsHistoryService history) {
        this.sampler = sampler;
        this.history = history;
    }

    @GET
    @Operation(
            summary = "Sampling state and the readings collected so far",
            description = "Readings are oldest first, which is the order a chart plots.")
    @APIResponse(responseCode = "200", description = "Sampling state and readings")
    @RequiresPermission(value = Permission.MONITORING_READ, connection = "connectionId")
    // Uni over a value already in memory: every endpoint that requires a permission has to
    // return one, because the check is itself asynchronous and has to be folded into the
    // result rather than thrown around it.
    public Uni<MonitoringState> state(@PathParam("connectionId") Long connectionId) {
        return Uni.createFrom().item(sampler.state(connectionId));
    }

    @POST
    @Operation(
            summary = "Start sampling this target",
            description =
                    "Sampling is opt-in: it costs a round trip per interval whether anyone is"
                            + " watching or not. The first reading is taken immediately.")
    @APIResponse(responseCode = "200", description = "Sampling started")
    @APIResponse(responseCode = "404", description = "No connection with that id")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("monitoring.start")
    @RequiresPermission(value = Permission.MONITORING_MANAGE, connection = "connectionId")
    public Uni<MonitoringState> start(@PathParam("connectionId") Long connectionId) {
        return sampler.start(connectionId, MetricsSampler.Reason.DASHBOARD);
    }

    @DELETE
    @Operation(
            summary = "Stop sampling this target",
            description =
                    "Stops watching on this caller's account. A target that an alert rule is"
                            + " watching carries on being sampled, and the state says so rather"
                            + " than leaving a switch that looks stuck: a rule is what keeps"
                            + " sampling alive when nobody is looking.")
    @APIResponse(responseCode = "204", description = "Sampling stopped")
    @APIResponse(responseCode = "404", description = "This target was not being sampled")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("monitoring.stop")
    @RequiresPermission(value = Permission.MONITORING_MANAGE, connection = "connectionId")
    public Uni<Response> stop(@PathParam("connectionId") Long connectionId) {
        return Uni.createFrom()
                .item(
                        sampler.stop(connectionId, MetricsSampler.Reason.DASHBOARD)
                                ? Response.noContent().build()
                                : Response.status(Response.Status.NOT_FOUND).build());
    }

    @GET
    @Path("/history")
    @Operation(
            summary = "Readings over a window",
            description =
                    "Answered from memory when the window fits in it and from a store when it does"
                        + " not, and the answer says which — an hour of every reading taken and a"
                        + " month of averaged buckets are different claims. With no store"
                        + " configured, a window older than what memory holds is answered as"
                        + " nothing rather than as a shorter window.")
    @APIResponse(responseCode = "200", description = "The readings, oldest first")
    @RequiresPermission(value = Permission.MONITORING_READ, connection = "connectionId")
    public Uni<MetricsHistory> history(
            @PathParam("connectionId") Long connectionId,
            @QueryParam("from") String from,
            @QueryParam("to") String to,
            @QueryParam("points") @DefaultValue("240") int points) {
        Instant end = to == null || to.isBlank() ? Instant.now() : Instant.parse(to);
        Instant start =
                from == null || from.isBlank()
                        ? end.minus(Duration.ofHours(1))
                        : Instant.parse(from);
        return history.between(connectionId, start, end, Math.max(1, Math.min(points, 2000)));
    }

    @GET
    @Path("/sample")
    @Operation(
            summary = "One reading of this target's vital signs, taken now",
            description =
                    "A single reading rather than a subscription: the connection list draws a"
                        + " summary per target and has no use for a sampler running behind it. The"
                        + " reading is taken on request, so it costs one round trip and stops"
                        + " costing anything the moment nobody asks.")
    @APIResponse(responseCode = "200", description = "The reading")
    @APIResponse(responseCode = "404", description = "No connection with that id")
    @RequiresPermission(value = Permission.MONITORING_READ, connection = "connectionId")
    public Uni<MetricsSample> sample(@PathParam("connectionId") Long connectionId) {
        return sampler.withMetrics(connectionId, (profile, metrics) -> metrics.sample(profile));
    }

    @GET
    @Path("/info")
    @Operation(
            summary = "Raw server statistics, grouped by section",
            description = "Everything the server will report, for a number the dashboard omits.")
    @APIResponse(responseCode = "200", description = "Statistics by section")
    @RequiresPermission(value = Permission.MONITORING_READ, connection = "connectionId")
    public Uni<Map<String, Map<String, String>>> info(
            @PathParam("connectionId") Long connectionId, @QueryParam("section") String section) {
        return sampler.withMetrics(
                connectionId, (profile, metrics) -> metrics.info(profile, section));
    }

    @GET
    @Path("/slowlog")
    @Operation(summary = "Commands the server recorded as slow, newest first")
    @APIResponse(responseCode = "200", description = "Slow commands")
    @RequiresPermission(value = Permission.MONITORING_READ, connection = "connectionId")
    public Uni<List<SlowCommand>> slowlog(
            @PathParam("connectionId") Long connectionId,
            @QueryParam("limit") @DefaultValue("50") int limit) {
        return sampler.withMetrics(
                connectionId, (profile, metrics) -> metrics.slowCommands(profile, limit));
    }

    @DELETE
    @Path("/slowlog")
    @Operation(
            summary = "Clear the slow log",
            description = "The only way to stop old entries crowding out new ones.")
    @APIResponse(responseCode = "204", description = "Slow log cleared")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("monitoring.slowlog.clear")
    @RequiresPermission(value = Permission.MONITORING_MANAGE, connection = "connectionId")
    public Uni<Void> clearSlowlog(@PathParam("connectionId") Long connectionId) {
        return sampler.withMetrics(
                connectionId, (profile, metrics) -> metrics.clearSlowCommands(profile));
    }

    @GET
    @Path("/clients")
    @Operation(summary = "Clients currently attached to the server")
    @APIResponse(responseCode = "200", description = "Attached clients")
    @RequiresPermission(value = Permission.MONITORING_READ, connection = "connectionId")
    public Uni<List<ClientConnection>> clients(@PathParam("connectionId") Long connectionId) {
        return sampler.withMetrics(connectionId, (profile, metrics) -> metrics.clients(profile));
    }

    @DELETE
    @Path("/clients/{clientId}")
    @Operation(
            summary = "Disconnect a client",
            description = "Answers 404 when the server has no client with that id any more.")
    @APIResponse(responseCode = "204", description = "The client was disconnected")
    @APIResponse(responseCode = "404", description = "No such client")
    @RolesAllowed(Roles.ADMIN)
    @Audited("monitoring.client.kill")
    @RequiresPermission(value = Permission.MONITORING_MANAGE, connection = "connectionId")
    public Uni<Response> killClient(
            @PathParam("connectionId") Long connectionId, @PathParam("clientId") String clientId) {
        return sampler.withMetrics(
                        connectionId, (profile, metrics) -> metrics.killClient(profile, clientId))
                .map(
                        killed ->
                                Boolean.TRUE.equals(killed)
                                        ? Response.noContent().build()
                                        : Response.status(Response.Status.NOT_FOUND).build());
    }

    @GET
    @Path("/big-keys")
    @Operation(
            summary = "The largest keys in a sample of the keyspace",
            description =
                    "A sample, never a census: measuring costs a round trip per key, so the report"
                            + " says how many keys it looked at.")
    @APIResponse(responseCode = "200", description = "The largest keys measured")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("monitoring.bigKeys")
    @RequiresPermission(value = Permission.MONITORING_READ, connection = "connectionId")
    public Uni<BigKeysReport> bigKeys(
            @PathParam("connectionId") Long connectionId,
            @QueryParam("sample") @DefaultValue("1000") int sample,
            @QueryParam("top") @DefaultValue("20") int top) {
        return sampler.biggestKeys(connectionId, sample, top);
    }
}
