package io.keydra.approvals.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.keydra.approvals.dto.ApprovalDtos.ApprovalRaised;
import io.keydra.approvals.entity.ApprovalKind;
import io.keydra.approvals.entity.ApprovalRequest;
import io.keydra.approvals.exception.ApprovalRequiredException;
import io.keydra.approvals.persistence.ApprovalRepository;
import io.keydra.common.vertx.OwnContext;
import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.events.dto.NotificationCategory;
import io.keydra.events.service.NotificationHub;
import io.keydra.security.service.AuditService;
import io.keydra.security.service.SecuritySettings;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The question a target asks before one person empties it on their own.
 *
 * <p>Called from the services beside {@link io.keydra.connections.service.GuardedTargets}, at the
 * same places and for the same reason phase 59 put that one there: a check in a resource is a check
 * the GraphQL surface and the scheduled work do not get. The two questions stay separate — naming a
 * target answers "is this the server I think it is", and this answers "should this happen at all" —
 * and a target can ask either, both or neither.
 */
@ApplicationScoped
public class Approvals {

    private final ApprovalRepository repository;
    private final SecuritySettings settings;
    private final SecurityIdentity identity;
    private final NotificationHub hub;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Vertx vertx;
    private final Duration ttl;

    @Inject
    Approvals(
            ApprovalRepository repository,
            SecuritySettings settings,
            SecurityIdentity identity,
            NotificationHub hub,
            AuditService audit,
            ObjectMapper json,
            Vertx vertx,
            @ConfigProperty(name = "keydra.approvals.ttl", defaultValue = "24h") Duration ttl) {
        this.repository = repository;
        this.settings = settings;
        this.identity = identity;
        this.hub = hub;
        this.audit = audit;
        this.json = json;
        this.vertx = vertx;
        this.ttl = ttl;
    }

    /**
     * Records the operation instead of letting it happen, where the target asks for two people.
     *
     * <p>Answers with nothing at all when it does not — which is the ordinary case, costs one field
     * read, and is why this can sit beside every operation that could empty something.
     *
     * @param primary the target the operation is about
     * @param second the other end of a migration, or null
     * @param payload everything the operation needs, which is stored and read by nothing but the
     *     handler that will carry it out
     */
    public Uni<Void> require(
            ConnectionProfile primary,
            ConnectionProfile second,
            ApprovalKind kind,
            Object payload) {
        return require(primary, second, kind, payload, true);
    }

    /**
     * The same, where the target the request is filed under is not one the operation changes.
     *
     * <p>One case: a copy between two targets. The source is what the request is about — it is the
     * endpoint's target and the row has to be filed somewhere — and a copy reads it and leaves
     * everything where it was, so its own flag has no say. That is the line phase 59 draws for the
     * naming, drawn once more in the same place.
     *
     * @param primaryCounts whether the primary target's own requirement applies
     */
    public Uni<Void> require(
            ConnectionProfile primary,
            ConnectionProfile second,
            ApprovalKind kind,
            Object payload,
            boolean primaryCounts) {
        ConnectionProfile asking = whichAsks(primaryCounts ? primary : null, second);
        if (asking == null) {
            return Uni.createFrom().voidItem();
        }
        return raise(primary, second, asking, kind, payload)
                .flatMap(raised -> Uni.createFrom().failure(new ApprovalRequiredException(raised)));
    }

    /**
     * Which end of this asked, or none.
     *
     * <p>An instance with enforcement switched off never asks. There are no accounts there to be a
     * second person, so the requirement could not be met by anybody — and an application that
     * refused everything rather than saying "this deployment is not checking" would be turning a
     * setting into an outage. Every other check in Keydra says yes there, and this one says "not
     * needed", which is the same answer in the shape this feature has.
     */
    private ConnectionProfile whichAsks(ConnectionProfile primary, ConnectionProfile second) {
        if (!settings.enabled()) {
            return null;
        }
        if (primary != null && primary.requiresApproval) {
            return primary;
        }
        return second != null && second.requiresApproval ? second : null;
    }

    /**
     * Writes the request down.
     *
     * <p>On a context of its own, which is not a detail. What is being refused may be inside a
     * transaction that is about to roll back — writing a schedule is — and a request that
     * disappeared with it would leave an operation refused, nobody told, and nothing recorded. A
     * fresh context inherits no session, so this row is committed on its own terms and survives
     * whatever happens to the request that caused it.
     */
    private Uni<ApprovalRaised> raise(
            ConnectionProfile primary,
            ConnectionProfile second,
            ConnectionProfile asking,
            ApprovalKind kind,
            Object payload) {
        ApprovalRequest request = new ApprovalRequest();
        request.kind = kind;
        request.connectionId = primary.id;
        request.secondConnectionId = second == null ? null : second.id;
        request.payload = serialise(payload);
        request.requestedBy = identity.isAnonymous() ? null : identity.getPrincipal().getName();
        request.requestedAt = Instant.now();
        request.expiresAt = request.requestedAt.plus(ttl);

        // Read here rather than inside, because inside is a context of its own and asking who is
        // signed in there is asking a question with no answer.
        String actor = audit.actor();

        return OwnContext.call(
                        vertx,
                        () ->
                                repository
                                        .save(request)
                                        .call(
                                                saved ->
                                                        audit.recordAs(
                                                                actor,
                                                                "approval.requested",
                                                                saved.connectionId,
                                                                // The kind and the target, and
                                                                // nothing from the payload: a glob
                                                                // and a key name are the contents
                                                                // of somebody's target, and the
                                                                // audit log is read by whoever can
                                                                // read any of it.
                                                                kind.name(),
                                                                true)))
                .invoke(
                        saved ->
                                hub.broadcast(
                                        NotificationCategory.APPROVAL_REQUESTED,
                                        saved.connectionId,
                                        Map.of("id", saved.id, "connectionId", saved.connectionId)))
                .map(
                        saved ->
                                new ApprovalRaised(
                                        saved.id,
                                        true,
                                        kind.name(),
                                        asking.name,
                                        saved.expiresAt,
                                        asking.name
                                                + " is a target that nobody empties on their own."
                                                + " This has been recorded and needs somebody else"
                                                + " to agree before it happens."));
    }

    /** What the operation needs, as JSON. Read by the handler that will carry it out. */
    private String serialise(Object payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            // Every payload here is a record of plain values. If one is not, the operation must
            // not proceed as though nothing had happened.
            throw new IllegalStateException("Could not record what this operation would do");
        }
    }
}
