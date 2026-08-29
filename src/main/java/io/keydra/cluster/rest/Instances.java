package io.keydra.cluster.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.cluster.dto.ClusterDtos.InstanceHealth;
import io.keydra.cluster.dto.ClusterDtos.InstanceSummary;
import io.keydra.cluster.dto.ClusterDtos.ReachabilityEventSummary;
import io.keydra.cluster.service.InstanceHealthService;
import io.keydra.cluster.service.InstanceRegistry;
import io.keydra.cluster.service.Reachability;
import io.keydra.security.Audited;
import io.keydra.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * How Keydra itself is doing.
 *
 * <p>Behind {@code instance:read} rather than open: the roster names hosts and the dependency list
 * names what this deployment is built on, and neither is a thing to hand to whoever asks. It is the
 * same permission that guards the instance's own settings, because this is the same subject seen
 * from the other side.
 */
@Path("/api/v1/instances")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Instances", description = "The Keydra instances themselves, and what they rest on")
@RolesAllowed(Roles.ADMIN)
public class Instances {

    private final InstanceHealthService health;
    private final InstanceRegistry instances;
    private final Reachability reachability;

    @Inject
    Instances(InstanceHealthService health, InstanceRegistry instances, Reachability reachability) {
        this.health = health;
        this.instances = instances;
        this.reachability = reachability;
    }

    @POST
    @Path("/reachability")
    @Operation(
            summary = "Ask everything Keydra reaches whether it is there, now",
            description =
                    "Identity providers and backup destinations only, and only the ones switched"
                            + " on. Refused when the last answer on record is seconds old: a button"
                            + " that can be held down is a way to make Keydra hammer somebody"
                            + " else's service.")
    @APIResponse(responseCode = "200", description = "Everything was asked")
    @APIResponse(responseCode = "429", description = "Asked too recently; nothing was sent")
    @RequiresPermission(Permission.INSTANCE_READ)
    @Audited("instance.reachability")
    public Uni<org.jboss.resteasy.reactive.RestResponse<Void>> checkReachability() {
        return reachability
                .checkNow()
                .map(
                        ran ->
                                ran
                                        ? org.jboss.resteasy.reactive.RestResponse.<Void>ok()
                                        : org.jboss.resteasy.reactive.RestResponse.<Void>status(
                                                429));
    }

    @GET
    @Path("/reachability/history")
    @Operation(
            summary = "When things Keydra reaches started and stopped answering",
            description =
                    "Changes rather than answers: a row is written when something starts or stops"
                        + " answering, not every time it is asked. Everything by default, because"
                        + " \"what changed\" is the question and the answer is short — narrowed to"
                        + " one thing where somebody is asking about one thing.")
    @APIResponse(responseCode = "200", description = "What changed, newest first")
    @RequiresPermission(Permission.INSTANCE_READ)
    public Uni<List<ReachabilityEventSummary>> reachabilityHistory(
            @QueryParam("kind") String kind,
            @QueryParam("subjectId") Long subjectId,
            @QueryParam("limit") Integer limit) {
        return reachability.history(kind, subjectId, limit == null ? 50 : limit);
    }

    @GET
    @Operation(
            summary = "Who is running and what they depend on",
            description =
                    "Every instance heard from recently, which one holds the work that must happen"
                            + " once, and whether Keydra can reach its own database and stores.")
    @APIResponse(responseCode = "200", description = "The instances and their dependencies")
    @RequiresPermission(Permission.INSTANCE_READ)
    public Uni<InstanceHealth> health() {
        return health.health();
    }

    /**
     * The roster on its own, without the probes.
     *
     * <p>For the question asked beside a target rather than on the instances page: which instances
     * hold this one. That reading wants the roster every few seconds and wants none of the outbound
     * probes the full answer makes, so it is a second endpoint rather than a parameter — a flag
     * that turned off half of a response would leave callers unable to say what they were getting.
     */
    @GET
    @Path("/roster")
    @Operation(
            summary = "Who is running, and what each of them is holding",
            description =
                    "Every instance heard from recently, with the sockets, streams, jobs and"
                            + " targets it reported on its last beat. Nothing is probed.")
    @APIResponse(responseCode = "200", description = "The instances")
    @RequiresPermission(Permission.INSTANCE_READ)
    public Uni<List<InstanceSummary>> roster() {
        return health.roster();
    }

    /**
     * Asks an instance to stop taking new work.
     *
     * <p>Almost never the instance answering: two Keydras do not connect to each other, so this
     * writes a row that the instance it names reads on its next beat. The reply says the
     * instruction was written, which is a different thing from it having taken effect — that
     * follows a beat later, and the roster is where it is watched.
     */
    @POST
    @Path("/{id}/drain")
    @Operation(
            summary = "Take an instance out of service",
            description =
                    "The instance stops reporting itself ready, so a load balancer sends it no new"
                        + " browsers; it gives up the work that must happen once, so another"
                        + " instance picks that up within a beat; and it takes on no new long work."
                        + " Everything it is already holding stays with it until it is stopped.")
    @APIResponse(responseCode = "204", description = "The instance has been asked to drain")
    @APIResponse(responseCode = "404", description = "No instance of that name is running")
    @RequiresPermission(Permission.INSTANCE_DRAIN)
    @Audited("instance.drain")
    public Uni<Void> drain(@PathParam("id") String id) {
        return instances.drain(id, true).map(Instances::orNotFound);
    }

    /**
     * Puts it back into service, because a drain started for the wrong reason has to be undoable.
     */
    @DELETE
    @Path("/{id}/drain")
    @Operation(
            summary = "Put an instance back into service",
            description =
                    "The instance reports itself ready again and takes part in the chores again on"
                            + " its next beat.")
    @APIResponse(responseCode = "204", description = "The instance has been asked to resume")
    @APIResponse(responseCode = "404", description = "No instance of that name is running")
    @RequiresPermission(Permission.INSTANCE_DRAIN)
    @Audited("instance.resume")
    public Uni<Void> resume(@PathParam("id") String id) {
        return instances.drain(id, false).map(Instances::orNotFound);
    }

    /**
     * A name nobody is running under is a mistake rather than a silent success.
     *
     * <p>Thrown rather than returned as a status, so the audit log records the refusal too: an
     * endpoint that answered 404 with a successful entry beside it would be a log saying an
     * instance was drained when none was.
     */
    private static Void orNotFound(boolean found) {
        if (!found) {
            throw new NotFoundException("No such instance");
        }
        return null;
    }
}
