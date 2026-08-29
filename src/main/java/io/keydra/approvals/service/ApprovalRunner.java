package io.keydra.approvals.service;

import io.keydra.approvals.entity.ApprovalRequest;
import io.keydra.approvals.entity.ApprovalState;
import io.keydra.approvals.persistence.ApprovalRepository;
import io.keydra.authz.entity.Permission;
import io.keydra.common.vertx.OwnContext;
import io.keydra.events.dto.NotificationCategory;
import io.keydra.events.service.NotificationHub;
import io.keydra.security.service.AuditService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Carries out a request somebody has agreed to.
 *
 * <p>Detached from the agreeing, on a context of its own. A purge of a large keyspace is a minute
 * or ten, and a colleague pressing approve should not be holding an HTTP request open for it — the
 * same decision the scheduled copy makes, and the progress goes to the same place it always did.
 * The context matters for the ordinary reason: the work ends in Hibernate, and joining the context
 * of a request that is about to finish means joining a session that is about to close.
 */
@ApplicationScoped
public class ApprovalRunner {

    private static final Logger LOG = Logger.getLogger(ApprovalRunner.class);

    /** How much of a failure is worth keeping. Enough to recognise it, not a stack trace. */
    private static final int DETAIL_LIMIT = 900;

    private final ApprovalRepository repository;
    private final ApprovalWorkshop workshop;
    private final ApprovalGuard guard;
    private final NotificationHub hub;
    private final AuditService audit;
    private final Vertx vertx;

    @Inject
    ApprovalRunner(
            ApprovalRepository repository,
            ApprovalWorkshop workshop,
            ApprovalGuard guard,
            NotificationHub hub,
            AuditService audit,
            Vertx vertx) {
        this.repository = repository;
        this.workshop = workshop;
        this.guard = guard;
        this.hub = hub;
        this.audit = audit;
        this.vertx = vertx;
    }

    /** Starts the work and returns; how it ended arrives on the hub. */
    public void carryOut(ApprovalRequest request) {
        announce(request);
        OwnContext.run(
                vertx,
                () -> attempt(request),
                failure -> LOG.errorf(failure, "Approved request %d could not be run", request.id));
    }

    private Uni<Void> attempt(ApprovalRequest request) {
        return guard.missing(request)
                .flatMap(
                        missing ->
                                missing != null
                                        ? finish(
                                                request,
                                                ApprovalState.FAILED,
                                                request.requestedBy
                                                        + " no longer holds "
                                                        + missing.id()
                                                        + where(missing))
                                        : run(request));
    }

    private Uni<Void> run(ApprovalRequest request) {
        return workshop.workFor(request.kind)
                .run(request)
                .onItemOrFailure()
                .transformToUni(
                        (what, failure) ->
                                failure == null
                                        ? finish(request, ApprovalState.DONE, what)
                                        : finish(request, ApprovalState.FAILED, describe(failure)));
    }

    /**
     * Where a permission is held, which is not decoration.
     *
     * <p>"keys:delete on this target" and "script:run on this instance" are two different things to
     * go and ask an administrator for, and a message that said "on this target" for both would send
     * somebody to the wrong page.
     */
    private static String where(Permission permission) {
        return permission.level() == Permission.Level.INSTANCE
                ? " on this instance"
                : " on this target";
    }

    private Uni<Void> finish(ApprovalRequest request, ApprovalState state, String detail) {
        String trimmed =
                detail == null || detail.length() <= DETAIL_LIMIT
                        ? detail
                        : detail.substring(0, DETAIL_LIMIT);
        request.state = state;
        request.detail = trimmed;
        return repository
                .finish(request.id, state, trimmed)
                .call(
                        () ->
                                audit.recordAs(
                                        // The work ran on the requester's access, so the log says
                                        // so. Whoever agreed to it is on the row the approval
                                        // endpoint wrote, which is a different act.
                                        request.requestedBy,
                                        "approval."
                                                + state.name().toLowerCase(java.util.Locale.ROOT),
                                        request.connectionId,
                                        request.kind.name(),
                                        state == ApprovalState.DONE))
                .invoke(() -> announce(request));
    }

    /** Says the request changed, carrying its id and nothing else worth redacting. */
    public void announce(ApprovalRequest request) {
        hub.broadcast(
                NotificationCategory.APPROVAL_CHANGED,
                request.connectionId,
                Map.of(
                        "id", request.id,
                        "connectionId", request.connectionId,
                        "state", request.state.name()));
    }

    private static String describe(Throwable failure) {
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
