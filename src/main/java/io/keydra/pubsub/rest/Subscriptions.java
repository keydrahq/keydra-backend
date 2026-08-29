package io.keydra.pubsub.rest;

import io.keydra.pubsub.dto.Subscription;
import io.keydra.pubsub.service.SubscriptionRegistry;
import io.keydra.security.Roles;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/**
 * Every open subscription, across all targets.
 *
 * <p>Separate from the per-connection resource because it answers a different question: not "what
 * is this target listening to" but "what is this server holding open", which is what a page needs
 * to show before a target has been chosen.
 */
@Path("/api/v1/subscriptions")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Pub/Sub", description = "Channel subscriptions and publishing")
@RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
public class Subscriptions {

    private final SubscriptionRegistry registry;

    @Inject
    Subscriptions(SubscriptionRegistry registry) {
        this.registry = registry;
    }

    @GET
    @Operation(summary = "Every subscription this server currently holds open")
    @APIResponse(responseCode = "200", description = "Open subscriptions")
    public List<Subscription> list() {
        return registry.subscriptions();
    }
}
