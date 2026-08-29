package io.keydra.monitoring.graphql;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.authz.service.CallerPermissions;
import io.keydra.common.graphql.OneAtATime;
import io.keydra.connections.dto.ConnectionResponse;
import io.keydra.connections.service.ConnectionService;
import io.keydra.engine.ClientConnection;
import io.keydra.engine.MetricsSample;
import io.keydra.engine.SlowCommand;
import io.keydra.monitoring.dto.BigKeysReport;
import io.keydra.monitoring.dto.MetricsHistory;
import io.keydra.monitoring.dto.MonitoringState;
import io.keydra.monitoring.dto.TargetSample;
import io.keydra.monitoring.service.MetricsHistoryService;
import io.keydra.monitoring.service.MetricsSampler;
import io.keydra.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.graphql.DefaultValue;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

/**
 * What a target is doing, and what it did.
 *
 * <p>The dashboard is the page that asked for the most at once — its state, a sample, a window of
 * history, the slow log and the client list, each its own request every time it refreshed. Asking
 * for them together is what the second surface is for.
 *
 * <p>Reading and changing are guarded differently, so the annotations are per operation. Watching a
 * server is not the same right as making it start sampling, and killing somebody's client
 * connection is not the same right as looking at the list of them.
 *
 * <p>Transport only, calling the same services the resources call.
 */
@GraphQLApi
@OneAtATime
public class MonitoringQueries {

    /** The most points a chart can ask for. A window is a window; a caller does not get the lot. */
    private static final int MAX_POINTS = 2000;

    private final MetricsSampler sampler;
    private final MetricsHistoryService history;
    private final ConnectionService connections;
    private final CallerPermissions caller;

    @Inject
    MonitoringQueries(
            MetricsSampler sampler,
            MetricsHistoryService history,
            ConnectionService connections,
            CallerPermissions caller) {
        this.sampler = sampler;
        this.history = history;
        this.connections = connections;
        this.caller = caller;
    }

    @Query("monitoring")
    @Description("Whether a target is being sampled, and since when")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.MONITORING_READ, connection = "connectionId")
    public Uni<MonitoringState> monitoring(@Name("connectionId") Long connectionId) {
        return Uni.createFrom().item(sampler.state(connectionId));
    }

    /**
     * One reading from every target the caller can see, in one question.
     *
     * <p>The overview asked this per target, which is a request per server on every visit: an
     * estate of twenty made twenty. They are one question — "how is the fleet" — and the server is
     * better placed to answer it, because it can take the readings at once where twenty browser
     * requests queue behind each other.
     *
     * <p>Which targets is not an argument. The caller sees what the caller sees, and letting a
     * request name ids would mean checking each of them against that anyway.
     *
     * <p>The permission is checked here rather than by an annotation, and that is the difference
     * between this operation and its per-target neighbours. {@code MONITORING_READ} is granted
     * against a target or a group of them, so asking for it without naming one resolves only the
     * grants made at instance level — which is none of them, for anybody whose access comes from a
     * grant rather than a built-in role. It failed closed, so nothing was ever exposed; what it did
     * was refuse the overview to exactly the people the grant model exists for. Asked per target,
     * it is the same question the neighbours ask.
     */
    @Query("fleet")
    @Description("One reading from every target the caller can see")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    public Uni<List<TargetSample>> fleet() {
        return connections
                .list()
                .map(profiles -> profiles.stream().map(ConnectionResponse::id).toList())
                .flatMap(visible -> caller.holdingAll(Permission.MONITORING_READ, visible))
                .flatMap(sampler::fleet);
    }

    @Query("monitoringSample")
    @Description("One reading, taken now")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.MONITORING_READ, connection = "connectionId")
    public Uni<MetricsSample> monitoringSample(@Name("connectionId") Long connectionId) {
        return sampler.withMetrics(connectionId, (profile, metrics) -> metrics.sample(profile));
    }

    /**
     * A window of readings.
     *
     * <p>Not a connection, and this is the case where cursors would be the wrong tool: a chart does
     * not page through history, it asks for a period and a number of points and gets that period
     * evenly divided. Paging it would hand back a slice of an axis.
     */
    @Query("monitoringHistory")
    @Description("Readings between two moments, evenly divided into points")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.MONITORING_READ, connection = "connectionId")
    public Uni<MetricsHistory> monitoringHistory(
            @Name("connectionId") Long connectionId,
            @Name("from") @Description("The start of the window") Instant from,
            @Name("to") @Description("The end of the window") Instant to,
            @Name("points") @DefaultValue("240") @Description("How many points to divide it into")
                    Integer points) {
        Instant end = to == null ? Instant.now() : to;
        Instant start = from == null ? end.minusSeconds(3600) : from;
        int wanted = points == null ? 240 : Math.max(1, Math.min(points, MAX_POINTS));
        return history.between(connectionId, start, end, wanted);
    }

    @Query("slowLog")
    @Description("The commands the server itself recorded as slow")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.MONITORING_READ, connection = "connectionId")
    public Uni<List<SlowCommand>> slowLog(
            @Name("connectionId") Long connectionId,
            @Name("limit") @DefaultValue("50") @Description("How many entries to read")
                    Integer limit) {
        int wanted = limit == null ? 50 : Math.max(1, Math.min(limit, 1000));
        return sampler.withMetrics(
                connectionId, (profile, metrics) -> metrics.slowCommands(profile, wanted));
    }

    @Query("clients")
    @Description("Who is connected to the target right now")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.MONITORING_READ, connection = "connectionId")
    public Uni<List<ClientConnection>> clients(@Name("connectionId") Long connectionId) {
        return sampler.withMetrics(connectionId, (profile, metrics) -> metrics.clients(profile));
    }

    /**
     * The biggest keys, found by sampling.
     *
     * <p>A query rather than a mutation despite the work it does: it reads, changes nothing, and
     * asking twice gives the same kind of answer. That it is expensive is a reason for the caller
     * to ask rarely, not a reason to call it something it is not.
     */
    @Query("biggestKeys")
    @Description("The biggest keys found by sampling the keyspace")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.MONITORING_READ, connection = "connectionId")
    public Uni<BigKeysReport> biggestKeys(
            @Name("connectionId") Long connectionId,
            @Name("sample") @DefaultValue("1000") @Description("How many keys to look at")
                    Integer sample,
            @Name("top") @DefaultValue("20") @Description("How many to report") Integer top) {
        return sampler.biggestKeys(
                connectionId, sample == null ? 1000 : sample, top == null ? 20 : top);
    }

    // --- Changing what it does ----------------------------------------------

    @Mutation("startMonitoring")
    @Description("Starts sampling a target on a clock")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.MONITORING_MANAGE, connection = "connectionId")
    public Uni<MonitoringState> startMonitoring(@Name("connectionId") Long connectionId) {
        return sampler.start(connectionId, MetricsSampler.Reason.DASHBOARD);
    }

    @Mutation("stopMonitoring")
    @Description("Stops sampling a target")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.MONITORING_MANAGE, connection = "connectionId")
    public Uni<Boolean> stopMonitoring(@Name("connectionId") Long connectionId) {
        return Uni.createFrom().item(sampler.stop(connectionId, MetricsSampler.Reason.DASHBOARD));
    }

    @Mutation("clearSlowLog")
    @Description("Empties the server's own slow log")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.MONITORING_MANAGE, connection = "connectionId")
    public Uni<Boolean> clearSlowLog(@Name("connectionId") Long connectionId) {
        return sampler.withMetrics(
                        connectionId, (profile, metrics) -> metrics.clearSlowCommands(profile))
                .replaceWith(true);
    }

    @Mutation("killClient")
    @Description("Closes one client's connection to the target")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.MONITORING_MANAGE, connection = "connectionId")
    public Uni<Boolean> killClient(
            @Name("connectionId") Long connectionId, @Name("clientId") String clientId) {
        return sampler.withMetrics(
                connectionId, (profile, metrics) -> metrics.killClient(profile, clientId));
    }
}
