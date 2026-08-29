package io.keydra.pubsub.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.pubsub.dto.PublishRequest;
import io.keydra.pubsub.dto.PublishResult;
import io.keydra.pubsub.dto.Subscription;
import io.keydra.pubsub.dto.SubscriptionRequest;
import io.keydra.pubsub.service.SubscriptionRegistry;
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
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Subscribing to and publishing on a target's channels.
 *
 * <p>Messages themselves do not come back through here. A subscription is long-lived and shared by
 * every tab looking at the target, so what arrives is broadcast over the notification hub the rest
 * of the application already listens on, and these endpoints only open, close and describe it.
 */
@Path("/api/v1/connections/{connectionId}/pubsub")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Pub/Sub", description = "Channel subscriptions and publishing")
@RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
public class PubSub {

    private final SubscriptionRegistry registry;

    @Inject
    PubSub(SubscriptionRegistry registry) {
        this.registry = registry;
    }

    @POST
    @Path("/subscription")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Listen on a target's channels",
            description =
                    "Replaces whatever this target was already subscribed to. Messages arrive over"
                            + " the notification hub, not in this response.")
    @APIResponse(responseCode = "200", description = "The subscription now open")
    @APIResponse(responseCode = "404", description = "No connection with that id")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("pubsub.subscribe")
    @RequiresPermission(value = Permission.PUBSUB_SUBSCRIBE, connection = "connectionId")
    public Uni<Subscription> subscribe(
            @PathParam("connectionId") Long connectionId, @Valid SubscriptionRequest request) {
        return registry.subscribe(connectionId, request);
    }

    @DELETE
    @Path("/subscription")
    @Operation(summary = "Stop listening on this target")
    @APIResponse(responseCode = "204", description = "The subscription was closed")
    @APIResponse(responseCode = "404", description = "This target had no open subscription")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("pubsub.unsubscribe")
    @RequiresPermission(value = Permission.PUBSUB_SUBSCRIBE, connection = "connectionId")
    public Uni<Response> unsubscribe(@PathParam("connectionId") Long connectionId) {
        return Uni.createFrom()
                .item(
                        registry.unsubscribe(connectionId)
                                ? Response.noContent().build()
                                : Response.status(Response.Status.NOT_FOUND).build());
    }

    @GET
    @Path("/subscription")
    @Operation(summary = "What this target is currently subscribed to")
    @APIResponse(responseCode = "200", description = "The open subscription")
    @APIResponse(responseCode = "404", description = "This target has no open subscription")
    @RequiresPermission(value = Permission.PUBSUB_SUBSCRIBE, connection = "connectionId")
    public Uni<Response> subscription(@PathParam("connectionId") Long connectionId) {
        Subscription subscription = registry.subscription(connectionId);
        return Uni.createFrom()
                .item(
                        subscription == null
                                ? Response.status(Response.Status.NOT_FOUND).build()
                                : Response.ok(subscription).build());
    }

    @POST
    @Path("/publish")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Publish a message",
            description =
                    "Zero receivers is a normal answer: it means nobody was listening, not that"
                            + " anything failed.")
    @APIResponse(responseCode = "200", description = "How many subscribers received it")
    @APIResponse(responseCode = "404", description = "No connection with that id")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @Audited("pubsub.publish")
    @RequiresPermission(value = Permission.PUBSUB_PUBLISH, connection = "connectionId")
    public Uni<PublishResult> publish(
            @PathParam("connectionId") Long connectionId, @Valid PublishRequest request) {
        return registry.publish(connectionId, request.channel(), request.payload());
    }
}
