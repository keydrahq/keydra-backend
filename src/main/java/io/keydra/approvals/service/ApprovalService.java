package io.keydra.approvals.service;

import io.keydra.approvals.dto.ApprovalDtos.ApprovalSummary;
import io.keydra.approvals.entity.ApprovalRequest;
import io.keydra.approvals.entity.ApprovalState;
import io.keydra.approvals.exception.ApprovalRefusedException;
import io.keydra.approvals.persistence.ApprovalRepository;
import io.keydra.authz.entity.Permission;
import io.keydra.authz.service.CallerPermissions;
import io.keydra.connections.persistence.ConnectionProfileRepository;
import io.keydra.security.service.SecuritySettings;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reading the requests, and answering them.
 *
 * <p>Who may answer one is not an annotation on an endpoint, because it depends on the row: a
 * request to purge needs {@code keys:delete}, one to import needs {@code transfer:import}, and one
 * to migrate needs {@code migration:run} on both ends. That is phase 59's finding restated — a
 * permission checked against something the caller supplied is a permission checked against nothing
 * — so the decision is made here, against what the request actually says.
 */
@ApplicationScoped
public class ApprovalService {

    /** How many a page shows. Enough to see everything open, not enough to be a report. */
    private static final int LIMIT = 200;

    private final ApprovalRepository repository;
    private final ConnectionProfileRepository connections;
    private final CallerPermissions caller;
    private final SecurityIdentity identity;
    private final SecuritySettings settings;
    private final ApprovalRunner runner;
    private final ApprovalWorkshop workshop;

    @Inject
    ApprovalService(
            ApprovalRepository repository,
            ConnectionProfileRepository connections,
            CallerPermissions caller,
            SecurityIdentity identity,
            SecuritySettings settings,
            ApprovalRunner runner,
            ApprovalWorkshop workshop) {
        this.repository = repository;
        this.connections = connections;
        this.caller = caller;
        this.identity = identity;
        this.settings = settings;
        this.runner = runner;
        this.workshop = workshop;
    }

    /**
     * The requests the caller can see.
     *
     * <p>Filtered by visibility rather than by a permission of its own, the same way the schedules
     * and the rules are: a request is about a target, and a target somebody cannot reach is one
     * whose pending operations are none of their business. A migration is about two, and both have
     * to be visible — otherwise the list is a way of learning the name of a server somebody was not
     * given.
     */
    @WithSession
    public Uni<List<ApprovalSummary>> list(boolean onlyOpen) {
        return repository.all(onlyOpen, LIMIT).flatMap(this::onlyVisible).flatMap(this::describe);
    }

    @WithSession
    public Uni<ApprovalSummary> one(Long id) {
        return repository
                .byId(id)
                .flatMap(
                        request ->
                                request == null
                                        ? Uni.createFrom().<ApprovalSummary>nullItem()
                                        : onlyVisible(List.of(request))
                                                .flatMap(this::describe)
                                                .map(all -> all.isEmpty() ? null : all.get(0)));
    }

    private Uni<List<ApprovalRequest>> onlyVisible(List<ApprovalRequest> requests) {
        List<Long> ids =
                requests.stream()
                        .flatMap(
                                one ->
                                        java.util.stream.Stream.of(
                                                one.connectionId, one.secondConnectionId))
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList();
        return caller.visible(ids)
                .map(
                        visible ->
                                requests.stream()
                                        .filter(
                                                one ->
                                                        visible.contains(one.connectionId)
                                                                && (one.secondConnectionId == null
                                                                        || visible.contains(
                                                                                one.secondConnectionId)))
                                        .toList());
    }

    /**
     * Turns rows into what a page reads, resolving names once for the whole list.
     *
     * <p>One after another rather than at once, for the reason every loop like this one in Keydra
     * is sequential: a reactive session runs one query at a time.
     */
    private Uni<List<ApprovalSummary>> describe(List<ApprovalRequest> requests) {
        Map<Long, String> names = new HashMap<>();
        List<Long> wanted =
                requests.stream()
                        .flatMap(
                                one ->
                                        java.util.stream.Stream.of(
                                                one.connectionId, one.secondConnectionId))
                        .filter(java.util.Objects::nonNull)
                        .distinct()
                        .toList();

        Uni<Void> resolved = Uni.createFrom().voidItem();
        for (Long id : wanted) {
            resolved =
                    resolved.flatMap(
                            ignored ->
                                    connections
                                            .findById(id)
                                            .invoke(
                                                    profile -> {
                                                        if (profile != null) {
                                                            names.put(id, profile.name);
                                                        }
                                                    })
                                            .replaceWithVoid());
        }

        Uni<List<ApprovalSummary>> described = resolved.map(ignored -> new ArrayList<>());
        for (ApprovalRequest request : requests) {
            described =
                    described.flatMap(
                            soFar ->
                                    mayDecide(request)
                                            .map(
                                                    can -> {
                                                        soFar.add(summarise(request, names, can));
                                                        return soFar;
                                                    }));
        }
        return described.map(List::copyOf);
    }

    private ApprovalSummary summarise(
            ApprovalRequest request, Map<Long, String> names, boolean canDecide) {
        ApprovalWork work = workshop.workFor(request.kind);
        return new ApprovalSummary(
                request.id,
                request.kind.name(),
                request.state.name(),
                request.connectionId,
                names.get(request.connectionId),
                request.secondConnectionId,
                request.secondConnectionId == null ? null : names.get(request.secondConnectionId),
                describeSafely(work, request),
                particularsSafely(work, request),
                request.requestedBy,
                request.requestedAt,
                request.expiresAt,
                request.decidedBy,
                request.decidedAt,
                request.detail,
                isRequester(request),
                canDecide);
    }

    /**
     * The sentence, or the reason there is not one.
     *
     * <p>A payload written by a build that described this kind differently must not take out the
     * whole page: the rows around it are answerable and somebody is waiting on them. What it must
     * also not do is read as an ordinary description — so it says what is wrong, and the guard says
     * the same thing again if anybody agrees to it anyway.
     */
    private static String describeSafely(ApprovalWork work, ApprovalRequest request) {
        try {
            return work.describe(request);
        } catch (RuntimeException unreadable) {
            return "This request was written by a different version of Keydra and cannot be read";
        }
    }

    private static List<String> particularsSafely(ApprovalWork work, ApprovalRequest request) {
        try {
            return work.particulars(request);
        } catch (RuntimeException unreadable) {
            return List.of();
        }
    }

    /**
     * Whether this caller may answer this request.
     *
     * <p>Whoever holds what the operation needs, on every target it touches, and is not the person
     * who asked. Not a permission of its own: one would have to be granted before the requirement
     * could be turned on, and until somebody did, a target with the flag set would be one nobody in
     * the building could empty — a control whose first effect is an outage. This way, turning it on
     * changes nothing about who may do what; the people who could each do it alone can do it in
     * pairs.
     *
     * <p>Never your own, whatever you hold. If only one person holds the permission, the operation
     * cannot happen — which is what an installation that asks for two people and has one has asked
     * for.
     */
    @WithSession
    public Uni<Boolean> mayDecide(ApprovalRequest request) {
        if (request.state != ApprovalState.PENDING || isRequester(request)) {
            return Uni.createFrom().item(false);
        }
        Set<Permission> needed = needed(request);
        return caller.forConnection(request.connectionId)
                .flatMap(
                        held -> {
                            if (!held.containsAll(needed)) {
                                return Uni.createFrom().item(false);
                            }
                            if (request.secondConnectionId == null) {
                                return Uni.createFrom().item(true);
                            }
                            return caller.forConnection(request.secondConnectionId)
                                    .map(there -> there.containsAll(needed));
                        });
    }

    /** What the kind requires, plus whatever this particular request adds to it. */
    private Set<Permission> needed(ApprovalRequest request) {
        Set<Permission> needed = new LinkedHashSet<>();
        needed.add(request.kind.required());
        needed.addAll(workshop.workFor(request.kind).alsoNeeds(request));
        return needed;
    }

    /** Agrees, and starts the work. */
    public Uni<ApprovalSummary> approve(Long id) {
        return decide(id, true, null);
    }

    /** Says no, and says why. */
    public Uni<ApprovalSummary> decline(Long id, String reason) {
        return decide(id, false, reason);
    }

    private Uni<ApprovalSummary> decide(Long id, boolean agreed, String reason) {
        return repository
                .byId(id)
                .flatMap(
                        request -> {
                            if (request == null) {
                                return Uni.createFrom()
                                        .failure(new ApprovalRefusedException("No such request"));
                            }
                            return mayDecide(request)
                                    .flatMap(
                                            may ->
                                                    Boolean.TRUE.equals(may)
                                                            ? act(request, agreed, reason)
                                                            : Uni.createFrom()
                                                                    .failure(
                                                                            new ApprovalRefusedException(
                                                                                    why(request))));
                        });
    }

    /**
     * The sentence a refusal gets.
     *
     * <p>Which of the three it is, because "you cannot answer this" sends somebody looking for an
     * administrator when the answer is that they asked for it themselves.
     */
    private String why(ApprovalRequest request) {
        if (request.state != ApprovalState.PENDING) {
            return "This request has already been answered";
        }
        if (isRequester(request)) {
            return "Nobody approves their own request. Somebody else who holds "
                    + request.kind.required().id()
                    + " on this target has to agree.";
        }
        return "Answering this needs " + request.kind.required().id() + " on this target";
    }

    private Uni<ApprovalSummary> act(ApprovalRequest request, boolean agreed, String reason) {
        String by = whoAmI();
        Instant now = Instant.now();
        ApprovalState to = agreed ? ApprovalState.RUNNING : ApprovalState.DECLINED;
        String detail = agreed ? null : trimmed(reason);

        return repository
                .claim(request.id, to, by, now, detail)
                .flatMap(
                        claimed -> {
                            if (!Boolean.TRUE.equals(claimed)) {
                                // Somebody else got there in the same second. The database
                                // decided, which is the only place that can.
                                return Uni.createFrom()
                                        .failure(
                                                new ApprovalRefusedException(
                                                        "This request has already been answered"));
                            }
                            request.state = to;
                            request.decidedBy = by;
                            request.decidedAt = now;
                            request.detail = detail;
                            if (agreed) {
                                runner.carryOut(request);
                            } else {
                                runner.announce(request);
                            }
                            return one(request.id);
                        });
    }

    /** The person who asked changes their mind. Theirs alone, and only while it is pending. */
    public Uni<ApprovalSummary> withdraw(Long id) {
        return repository
                .byId(id)
                .flatMap(
                        request -> {
                            if (request == null) {
                                return Uni.createFrom()
                                        .failure(new ApprovalRefusedException("No such request"));
                            }
                            if (!isRequester(request)) {
                                return Uni.createFrom()
                                        .failure(
                                                new ApprovalRefusedException(
                                                        "Only the person who asked can withdraw a"
                                                                + " request"));
                            }
                            return repository
                                    .claim(
                                            id,
                                            ApprovalState.WITHDRAWN,
                                            whoAmI(),
                                            Instant.now(),
                                            null)
                                    .flatMap(
                                            claimed ->
                                                    Boolean.TRUE.equals(claimed)
                                                            ? withdrawn(request)
                                                            : Uni.createFrom()
                                                                    .failure(
                                                                            new ApprovalRefusedException(
                                                                                    "This request"
                                                                                        + " has already"
                                                                                        + " been"
                                                                                        + " answered")));
                        });
    }

    private Uni<ApprovalSummary> withdrawn(ApprovalRequest request) {
        request.state = ApprovalState.WITHDRAWN;
        runner.announce(request);
        return one(request.id);
    }

    /**
     * Ends the requests nobody answered.
     *
     * <p>An ending rather than a deletion, and it is the point of having an expiry at all: the
     * failure this prevents is somebody believing an operation is arranged when it is never going
     * to happen, so the row stays and says what became of it. A purge agreed to three weeks late
     * would be agreement to a sentence rather than to a state of the world.
     *
     * <p>Here rather than on the sweeper, which is only the clock — the same arrangement {@code
     * Sessions} has with {@code SessionSweeper}, and what makes this something a test can ask for
     * instead of wait for.
     *
     * @return how many were ended
     */
    public Uni<Integer> expire() {
        Instant now = Instant.now();
        return repository.lapsed(now).flatMap(lapsed -> endEach(lapsed, now));
    }

    /**
     * One after another, for the reason every loop like this one in Keydra is sequential: a
     * reactive session runs one query at a time.
     */
    private Uni<Integer> endEach(List<ApprovalRequest> lapsed, Instant now) {
        Uni<Integer> ended = Uni.createFrom().item(0);
        for (ApprovalRequest request : lapsed) {
            ended =
                    ended.flatMap(
                            soFar ->
                                    repository
                                            .claim(
                                                    request.id,
                                                    ApprovalState.EXPIRED,
                                                    null,
                                                    now,
                                                    "Nobody answered before it expired")
                                            .map(
                                                    claimed -> {
                                                        if (!Boolean.TRUE.equals(claimed)) {
                                                            return soFar;
                                                        }
                                                        request.state = ApprovalState.EXPIRED;
                                                        runner.announce(request);
                                                        return soFar + 1;
                                                    }));
        }
        return ended;
    }

    private boolean isRequester(ApprovalRequest request) {
        String me = whoAmI();
        return me != null && me.equals(request.requestedBy);
    }

    /**
     * Who is asking, or nobody.
     *
     * <p>Anonymous only happens on an instance with enforcement switched off, where nothing raises
     * a request in the first place — but a null here would silently make everybody the requester of
     * every unattributed row, so it answers null and the comparison fails.
     */
    private String whoAmI() {
        if (!settings.enabled() || identity.isAnonymous() || identity.getPrincipal() == null) {
            return null;
        }
        return identity.getPrincipal().getName();
    }

    /** As wide as the column, so a long reason is trimmed here rather than by the database. */
    private static String trimmed(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        return reason.length() > 1000 ? reason.substring(0, 1000) : reason;
    }
}
