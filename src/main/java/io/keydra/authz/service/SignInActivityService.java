package io.keydra.authz.service;

import io.keydra.authz.dto.SignInActivity;
import io.keydra.authz.mapper.SignInActivityMapper;
import io.keydra.authz.persistence.SignInAttemptRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Reading the sign-in history, and keeping it from growing for ever.
 *
 * <p>Two audiences and they are asking different things. A person looks at their own and is
 * checking one fact: is every one of these mine. An administrator looks across everybody's and is
 * looking for a shape — one source trying many names, a run of failures that ended in a success. So
 * there are two questions rather than one filtered one, and the second needs a permission the first
 * does not.
 *
 * <p>Counted from an offset rather than a cursor, which is the opposite of what the audit log does
 * and is a difference worth stating. The audit log takes a row every time anybody does anything,
 * including while somebody is reading it, so page two overlaps page one as the ordinary case. This
 * table is written only when somebody signs in. Pages here are stable because nothing is happening
 * between them.
 */
@ApplicationScoped
public class SignInActivityService {

    private static final Logger LOG = Logger.getLogger(SignInActivityService.class);

    /** The most rows one question can ask for, whatever it asks for. */
    private static final int MAX_PAGE = 200;

    private final SignInAttemptRepository attempts;
    private final SignInActivityMapper mapper;
    private final CallerPermissions caller;
    private final Duration retention;

    @Inject
    SignInActivityService(
            SignInAttemptRepository attempts,
            SignInActivityMapper mapper,
            CallerPermissions caller,
            @ConfigProperty(name = "keydra.security.sign-in.retention") Duration retention) {
        this.attempts = attempts;
        this.mapper = mapper;
        this.caller = caller;
        this.retention = retention;
    }

    /**
     * The caller's own sign-ins, newest first.
     *
     * <p>Nobody else's, and there is no operation that reads somebody else's own list. What a
     * person's sign-ins say is where they work and when — the same reason there is no endpoint for
     * reading anybody else's sessions.
     */
    @WithSession
    public Uni<List<SignInActivity>> mine(int first, int offset) {
        return caller.currentUserId()
                .flatMap(
                        userId ->
                                userId == null
                                        ? Uni.createFrom().item(List.<SignInActivity>of())
                                        : attempts.forUser(userId, bounded(first), offset)
                                                .map(mapper::toActivity));
    }

    @WithSession
    public Uni<Long> mineCount() {
        return caller.currentUserId()
                .flatMap(
                        userId ->
                                userId == null
                                        ? Uni.createFrom().item(0L)
                                        : attempts.countForUser(userId));
    }

    /** Everything anybody's sign-in was flagged for, newest first. */
    @WithSession
    public Uni<List<SignInActivity>> flagged(Duration within, int first, int offset) {
        return attempts.flaggedSince(Instant.now().minus(within), bounded(first), offset)
                .map(mapper::toActivity);
    }

    @WithSession
    public Uni<Long> flaggedCount(Duration within) {
        return attempts.countFlaggedSince(Instant.now().minus(within));
    }

    /** Refusals across the instance, so a spray in progress is visible while it is happening. */
    @WithSession
    public Uni<List<SignInActivity>> failures(Duration within, int first) {
        return attempts.failuresSince(Instant.now().minus(within), bounded(first))
                .map(mapper::toActivity);
    }

    /**
     * Drops what is older than the retention window.
     *
     * <p>Called from the schedule, by whichever instance holds the lease. Kept long enough to see
     * an attack that is being made slowly, and not longer — a table of where somebody has been
     * signing in from, kept for ever, is a thing worth stealing rather than a thing worth having.
     */
    @WithTransaction
    public Uni<Integer> sweep() {
        return attempts.deleteOlderThan(Instant.now().minus(retention))
                .invoke(
                        gone -> {
                            if (gone > 0) {
                                LOG.debugf(
                                        "Dropped %d sign-in attempts past the retention window",
                                        gone);
                            }
                        });
    }

    private static int bounded(int first) {
        return Math.max(1, Math.min(first, MAX_PAGE));
    }
}
