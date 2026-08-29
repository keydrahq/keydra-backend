package io.keydra.security.service;

import io.keydra.common.graphql.Cursors;
import io.keydra.security.dto.AuditQuery;
import io.keydra.security.entity.AuditEvent;
import io.keydra.security.persistence.AuditRepository;
import io.keydra.security.persistence.AuditRows;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.jboss.logging.Logger;

/**
 * Records what was done, and by whom.
 *
 * <p>Recording never fails an operation. If the audit write breaks, the thing being audited has
 * already happened, and failing the request afterwards would leave the caller believing it did not
 * — which is a worse outcome than a gap in the log, and a gap the log itself reports.
 */
@ApplicationScoped
public class AuditService {

    private static final Logger LOG = Logger.getLogger(AuditService.class);

    private final AuditRepository events;
    private final SecurityIdentity identity;

    @Inject
    AuditService(AuditRepository events, SecurityIdentity identity) {
        this.events = events;
        this.identity = identity;
    }

    /** The name to record for whoever is making the current request. */
    public String actor() {
        return identity == null || identity.isAnonymous()
                ? OpenAccessAugmentor.ANONYMOUS
                : identity.getPrincipal().getName();
    }

    /**
     * Records one event.
     *
     * @param detail what was acted on — a key name, a profile name. Never a value: a value written
     *     to a target may be a secret, and copying it here would make the audit log a place secrets
     *     are kept.
     */
    @WithTransaction
    public Uni<Void> record(String action, Long connectionId, String detail, boolean succeeded) {
        return recordAs(actor(), action, connectionId, detail, succeeded);
    }

    /**
     * The same, for work whose actor is not whoever is on the current request.
     *
     * <p>Because there is no current request. An operation that was written down and carried out
     * later runs on a context of its own, where asking who is signed in is asking a question with
     * no answer — and the honest name to record is the one stored with the work: whoever asked for
     * it, or whoever agreed to it.
     */
    @WithTransaction
    public Uni<Void> recordAs(
            String actor, String action, Long connectionId, String detail, boolean succeeded) {
        return events.persist(
                        AuditEvent.of(
                                actor == null ? OpenAccessAugmentor.ANONYMOUS : actor,
                                action,
                                connectionId,
                                detail,
                                succeeded))
                .replaceWithVoid()
                .onFailure()
                .recoverWithUni(
                        failure -> {
                            LOG.errorf(
                                    failure,
                                    "Audit write failed for %s on connection %s",
                                    action,
                                    connectionId);
                            return Uni.createFrom().voidItem();
                        });
    }

    @WithSession
    public Uni<List<AuditEvent>> search(
            String actor, String action, Long connectionId, Instant since, int limit) {
        return events.search(actor, action, connectionId, since, limit);
    }

    /** One page of events with the total that matched, for a table that has a pager. */
    @WithSession
    public Uni<AuditRows> page(AuditQuery query, Cursors.Position after, int size) {
        return events.page(query, after, size);
    }

    @WithSession
    public Uni<List<String>> actions() {
        return events.actions();
    }
}
