package io.keydra.pubsub.graphql;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.common.graphql.OneAtATime;
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
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

/**
 * What a target is being listened to for, and sending something to it.
 *
 * <p>The messages themselves are not here. They arrive on the notification hub as they are
 * published, which is what a subscription is for and what an answer to a question cannot be. What
 * is here is the standing arrangement — which channels are being listened to, since when, how many
 * have arrived — and the two things that change it.
 *
 * <p>Subscribing is a mutation because it opens a connection to the target and keeps it. Publishing
 * is one because somebody receives what it sends. Neither is safe to run twice, which is the test.
 */
@GraphQLApi
@OneAtATime
public class PubSubQueries {

    private final SubscriptionRegistry registry;

    @Inject
    PubSubQueries(SubscriptionRegistry registry) {
        this.registry = registry;
    }

    /** Null when nothing is being listened to, which is the ordinary state rather than an error. */
    @Query("subscription")
    @Description("What this target is being listened to for, or nothing")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.PUBSUB_SUBSCRIBE, connection = "connectionId")
    public Uni<Subscription> subscription(@Name("connectionId") Long connectionId) {
        return Uni.createFrom().item(registry.subscription(connectionId));
    }

    @Mutation("subscribe")
    @Description("Starts listening to channels or patterns on a target")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.PUBSUB_SUBSCRIBE, connection = "connectionId")
    @Audited("pubsub.subscribe")
    public Uni<Subscription> subscribe(
            @Name("connectionId") Long connectionId,
            @Name("subscription") @Valid SubscriptionRequest subscription) {
        return registry.subscribe(connectionId, subscription);
    }

    @Mutation("unsubscribe")
    @Description("Stops listening; answers whether anything was listening")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.PUBSUB_SUBSCRIBE, connection = "connectionId")
    @Audited("pubsub.unsubscribe")
    public Uni<Boolean> unsubscribe(@Name("connectionId") Long connectionId) {
        return Uni.createFrom().item(registry.unsubscribe(connectionId));
    }

    @Mutation("publish")
    @Description("Sends a message to a channel and answers how many subscribers took it")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.PUBSUB_PUBLISH, connection = "connectionId")
    @Audited("pubsub.publish")
    public Uni<PublishResult> publish(
            @Name("connectionId") Long connectionId,
            @Name("channel") String channel,
            @Name("payload") String payload) {
        return registry.publish(connectionId, channel, payload);
    }
}
