package io.keydra.authz.service;

import io.keydra.authz.entity.Grant;
import io.keydra.authz.entity.Permission;
import io.keydra.authz.entity.RoleDefinition;
import io.keydra.authz.entity.ScopeType;
import io.keydra.authz.entity.SubjectType;
import io.keydra.authz.persistence.AuthzRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What somebody may do to a target.
 *
 * <p>The answer is the union of the permissions of every role granted to them, or to any group
 * containing them, on that target or on anything containing it. Two graphs are walked to find out:
 * upward through the groups a person is in, and upward through the groups a target is in.
 *
 * <p>Both walks are breadth-first with a visited set, which is what keeps a cycle from being a
 * hang. Cycles are refused on write, but a resolver that would loop if one existed is a resolver
 * whose correctness depends on another class never having a bug.
 */
@ApplicationScoped
public class PermissionResolver {

    /**
     * How deep a group nesting is followed before it is treated as a mistake rather than a design.
     */
    private static final int MAX_DEPTH = 32;

    private final AuthzRepository repository;

    @Inject
    PermissionResolver(AuthzRepository repository) {
        this.repository = repository;
    }

    /**
     * Everything this user may do to this target.
     *
     * @param connectionId the target, or null to ask only about the instance
     */
    @WithSession
    public Uni<Set<Permission>> permissionsFor(Long userId, Long connectionId) {
        return subjectsOf(userId)
                .flatMap(
                        subjects ->
                                scopesReaching(connectionId)
                                        .flatMap(scopes -> resolve(subjects, scopes)));
    }

    /** Everything this user may do to Keydra itself. */
    public Uni<Set<Permission>> instancePermissionsFor(Long userId) {
        return permissionsFor(userId, null);
    }

    /**
     * Everything this user may do anywhere, ignoring where.
     *
     * <p>Not an authorization answer — no endpoint should decide anything from it, because it says
     * nothing about which target. It exists for the coarse role names an authenticated session
     * carries: those have to describe the person rather than a request, and a person who may write
     * keys on one server is an operator even on the page where they may not.
     */
    @WithSession
    public Uni<Set<Permission>> permissionsAnywhere(Long userId) {
        if (userId == null) {
            return Uni.createFrom().item(Set.of());
        }
        return subjectsOf(userId)
                .flatMap(this::grantsOf)
                .flatMap(
                        grants ->
                                grants.isEmpty()
                                        ? Uni.createFrom().item(Set.of())
                                        : permissionsOfRoles(grants));
    }

    /**
     * Which of these targets the user can see at all.
     *
     * <p>Visibility is not a separate flag: a target somebody holds any permission on is one they
     * have a reason to know about, and one they hold none on is not. There being one fact rather
     * than two is what keeps them from drifting apart.
     */
    @WithSession
    public Uni<Set<Long>> visibleConnections(Long userId, List<Long> candidates) {
        if (candidates.isEmpty()) {
            return Uni.createFrom().item(Set.of());
        }
        return subjectsOf(userId)
                .flatMap(
                        subjects ->
                                grantsOf(subjects)
                                        .flatMap(
                                                grants -> {
                                                    // A grant on the instance reaches every
                                                    // target, so there is nothing to filter.
                                                    if (grants.stream()
                                                            .anyMatch(
                                                                    grant ->
                                                                            grant.scopeType
                                                                                    == ScopeType
                                                                                            .INSTANCE)) {
                                                        return Uni.createFrom()
                                                                .item(Set.copyOf(candidates));
                                                    }
                                                    return visibleUnder(grants, candidates);
                                                }));
    }

    /**
     * The subjects a grant could have been made to for it to reach this user: the user, and every
     * group containing them however indirectly.
     */
    private Uni<Subjects> subjectsOf(Long userId) {
        if (userId == null) {
            return Uni.createFrom().item(new Subjects(null, Set.of()));
        }
        return repository
                .membershipsOfUser(userId)
                .flatMap(
                        direct -> {
                            Set<Long> groups = new HashSet<>();
                            direct.forEach(edge -> groups.add(edge.groupId));
                            return climb(groups, new HashSet<>(groups), 0)
                                    .map(all -> new Subjects(userId, all));
                        });
    }

    /** One step up the subject graph, repeated until nothing new is found. */
    private Uni<Set<Long>> climb(Set<Long> frontier, Set<Long> found, int depth) {
        if (frontier.isEmpty() || depth >= MAX_DEPTH) {
            return Uni.createFrom().item(found);
        }
        return repository
                .membershipsOfGroups(frontier)
                .flatMap(
                        edges -> {
                            Set<Long> next = new HashSet<>();
                            edges.stream()
                                    .map(edge -> edge.groupId)
                                    .filter(found::add)
                                    .forEach(next::add);
                            return climb(next, found, depth + 1);
                        });
    }

    /**
     * The scopes a grant could have been made on for it to reach this target: the target, every
     * server group it is in, and every group above those.
     */
    private Uni<Scopes> scopesReaching(Long connectionId) {
        if (connectionId == null) {
            return Uni.createFrom().item(new Scopes(null, Set.of()));
        }
        return repository
                .serverGroupsOf(connectionId)
                .flatMap(
                        memberships -> {
                            Deque<Long> pending = new ArrayDeque<>();
                            memberships.stream()
                                    .map(member -> member.groupId)
                                    .forEach(pending::add);
                            return ascend(pending, new HashSet<>(pending), 0)
                                    .map(groups -> new Scopes(connectionId, groups));
                        });
    }

    /** One step up the scope tree, following each group's parent. */
    private Uni<Set<Long>> ascend(Deque<Long> pending, Set<Long> found, int depth) {
        if (pending.isEmpty() || depth >= MAX_DEPTH) {
            return Uni.createFrom().item(found);
        }
        Long next = pending.poll();
        return repository
                .serverGroup(next)
                .flatMap(
                        group -> {
                            if (group != null
                                    && group.parentId != null
                                    && found.add(group.parentId)) {
                                pending.add(group.parentId);
                            }
                            return ascend(pending, found, depth + 1);
                        });
    }

    private Uni<List<Grant>> grantsOf(Subjects subjects) {
        Uni<List<Grant>> direct =
                subjects.userId() == null
                        ? Uni.createFrom().item(List.of())
                        : repository.grantsFor(SubjectType.USER, List.of(subjects.userId()));
        return direct.flatMap(
                userGrants ->
                        repository
                                .grantsFor(SubjectType.GROUP, subjects.groupIds())
                                .map(
                                        groupGrants -> {
                                            List<Grant> all = new ArrayList<>(userGrants);
                                            all.addAll(groupGrants);
                                            return all;
                                        }));
    }

    private Uni<Set<Permission>> resolve(Subjects subjects, Scopes scopes) {
        return grantsOf(subjects)
                .flatMap(
                        grants -> {
                            List<Grant> reaching =
                                    grants.stream()
                                            .filter(grant -> reaches(grant, scopes))
                                            .toList();
                            if (reaching.isEmpty()) {
                                return Uni.createFrom().item(Set.of());
                            }
                            return permissionsOfRoles(reaching);
                        });
    }

    /** Whether a grant's scope contains the target being asked about. */
    private static boolean reaches(Grant grant, Scopes scopes) {
        return switch (grant.scopeType) {
            case INSTANCE -> true;
            case SERVER_GROUP -> scopes.groupIds().contains(grant.scopeId);
            case CONNECTION ->
                    scopes.connectionId() != null && scopes.connectionId().equals(grant.scopeId);
        };
    }

    private Uni<Set<Permission>> permissionsOfRoles(List<Grant> grants) {
        return repository
                .allRoles()
                .map(
                        roles -> {
                            Map<Long, RoleDefinition> byId = new HashMap<>();
                            roles.forEach(role -> byId.put(role.id, role));
                            Set<Permission> permissions = EnumSet.noneOf(Permission.class);
                            grants.forEach(
                                    grant -> {
                                        RoleDefinition role = byId.get(grant.roleId);
                                        if (role != null) {
                                            permissions.addAll(role.permissions);
                                        }
                                    });
                            return permissions;
                        });
    }

    /** The candidates reachable from the groups these grants name. */
    private Uni<Set<Long>> visibleUnder(List<Grant> grants, List<Long> candidates) {
        Set<Long> named =
                grants.stream()
                        .filter(grant -> grant.scopeType == ScopeType.CONNECTION)
                        .map(grant -> grant.scopeId)
                        .collect(HashSet::new, HashSet::add, HashSet::addAll);

        Set<Long> groups =
                grants.stream()
                        .filter(grant -> grant.scopeType == ScopeType.SERVER_GROUP)
                        .map(grant -> grant.scopeId)
                        .collect(HashSet::new, HashSet::add, HashSet::addAll);

        if (groups.isEmpty()) {
            named.retainAll(candidates);
            return Uni.createFrom().item(named);
        }
        // Every target in a granted group, and in the groups below it — which is the same
        // containment the scope walk uses, read downward instead of up.
        return descend(groups, new HashSet<>(groups), 0)
                .flatMap(repository::membersOfServerGroups)
                .map(
                        members -> {
                            members.stream().map(member -> member.connectionId).forEach(named::add);
                            named.retainAll(candidates);
                            return named;
                        });
    }

    /** Every group below these, so a grant on a parent reaches the targets in its children. */
    private Uni<Set<Long>> descend(Set<Long> frontier, Set<Long> found, int depth) {
        if (frontier.isEmpty() || depth >= MAX_DEPTH) {
            return Uni.createFrom().item(found);
        }
        return repository
                .allServerGroups()
                .map(
                        all -> {
                            Set<Long> next = new HashSet<>();
                            all.stream()
                                    .filter(group -> group.parentId != null)
                                    .filter(group -> frontier.contains(group.parentId))
                                    .map(group -> group.id)
                                    .filter(found::add)
                                    .forEach(next::add);
                            return next;
                        })
                .flatMap(next -> descend(next, found, depth + 1));
    }

    /** Who a grant could have been made to. */
    private record Subjects(Long userId, Set<Long> groupIds) {}

    /** What a grant could have been made on. */
    private record Scopes(Long connectionId, Set<Long> groupIds) {}
}
