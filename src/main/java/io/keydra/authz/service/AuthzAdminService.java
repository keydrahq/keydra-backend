package io.keydra.authz.service;

import io.keydra.authz.dto.AuthzDtos.GrantRequest;
import io.keydra.authz.dto.AuthzDtos.GrantSummary;
import io.keydra.authz.dto.AuthzDtos.GroupRequest;
import io.keydra.authz.dto.AuthzDtos.GroupSummary;
import io.keydra.authz.dto.AuthzDtos.MembershipRequest;
import io.keydra.authz.dto.AuthzDtos.RoleRequest;
import io.keydra.authz.dto.AuthzDtos.RoleSummary;
import io.keydra.authz.dto.AuthzDtos.ServerGroupRequest;
import io.keydra.authz.dto.AuthzDtos.ServerGroupSummary;
import io.keydra.authz.dto.AuthzDtos.SetupRequest;
import io.keydra.authz.dto.AuthzDtos.UserRequest;
import io.keydra.authz.dto.AuthzDtos.UserSummary;
import io.keydra.authz.entity.AppUser;
import io.keydra.authz.entity.BuiltInRole;
import io.keydra.authz.entity.Grant;
import io.keydra.authz.entity.GroupMembership;
import io.keydra.authz.entity.RoleDefinition;
import io.keydra.authz.entity.ScopeType;
import io.keydra.authz.entity.ServerGroup;
import io.keydra.authz.entity.ServerGroupMember;
import io.keydra.authz.entity.SubjectType;
import io.keydra.authz.entity.UserGroup;
import io.keydra.authz.exception.AuthzConflictException;
import io.keydra.authz.persistence.AuthzRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Creating and changing who may do what.
 *
 * <p>Every write here is checked against the shape the model needs to keep: a group cannot end up
 * containing itself, a built-in role cannot be edited, and a grant over Keydra itself cannot also
 * name a scope. Those are not validation niceties — each of them, unchecked, produces a permission
 * system that answers a question wrongly rather than one that refuses an edit.
 */
@ApplicationScoped
@ChangesAccess
public class AuthzAdminService {

    /** How far a nesting is followed when looking for a cycle. */
    private static final int MAX_DEPTH = 32;

    private final AuthzRepository repository;
    private final PasswordHasher hasher;
    private final SecurityIdentity identity;

    @Inject
    AuthzAdminService(
            AuthzRepository repository, PasswordHasher hasher, SecurityIdentity identity) {
        this.repository = repository;
        this.hasher = hasher;
        this.identity = identity;
    }

    // --- Users -------------------------------------------------------------

    @WithSession
    public Uni<List<UserSummary>> users() {
        return names().flatMap(
                        names ->
                                repository
                                        .allMemberships()
                                        .map(edges -> groupNamesByUser(edges, names))
                                        .flatMap(this::describeUsers));
    }

    private Uni<List<UserSummary>> describeUsers(Map<Long, List<String>> groupsByUser) {
        return repository
                .allUsers()
                .map(
                        users ->
                                users.stream()
                                        .map(
                                                user ->
                                                        toSummary(
                                                                user,
                                                                groupsByUser.getOrDefault(
                                                                        user.id, List.of())))
                                        .toList());
    }

    /**
     * Makes an account.
     *
     * <p>A password is allowed here and is no longer the ordinary way in. Since phase 22 an
     * administrator makes the account and Keydra invites the person, who chooses a password nobody
     * else ever types — which is what lets the audit log answer "who did this" about anything that
     * account goes on to do. An account made without one exists and cannot be signed into, which is
     * exactly what {@code LocalIdentities} already does with a passwordless row.
     *
     * <p>The field stays for the case it is still right for: an instance with no way to send mail
     * and no way to hand over a link.
     */
    @WithTransaction
    public Uni<UserSummary> createUser(UserRequest request) {
        return repository
                .userByUsername(request.username())
                .flatMap(
                        existing -> {
                            if (existing != null) {
                                return conflict(
                                        "A user called " + request.username() + " already exists");
                            }
                            AppUser user = new AppUser();
                            user.username = request.username();
                            user.displayName = request.displayName();
                            user.email = request.email();
                            user.provider = "local";
                            user.enabled = request.enabled() == null || request.enabled();
                            if (hasPassword(request)) {
                                user.passwordHash = hasher.hash(request.password());
                            }
                            return repository.save(user).map(saved -> toSummary(saved, List.of()));
                        });
    }

    /**
     * Changes a user.
     *
     * <p>An absent password keeps the stored one. The API never returns a password, so an edit form
     * arrives with that field empty, and reading the empty field as "clear it" would lock somebody
     * out every time their display name was corrected.
     */
    @WithTransaction
    public Uni<UserSummary> updateUser(Long id, UserRequest request) {
        return repository
                .user(id)
                .flatMap(
                        user -> {
                            if (user == null) {
                                return conflict("No such user");
                            }
                            user.displayName = request.displayName();
                            user.email = request.email();
                            if (request.enabled() != null) {
                                user.enabled = request.enabled();
                            }
                            if (hasPassword(request)) {
                                user.passwordHash = hasher.hash(request.password());
                            }
                            return Uni.createFrom().item(toSummary(user, List.of()));
                        });
    }

    @WithTransaction
    public Uni<Boolean> deleteUser(Long id) {
        return repository.deleteUser(id);
    }

    /**
     * Creates the first administrator, and only the first.
     *
     * <p>Refused the moment any account exists. That is the whole of what makes an unauthenticated
     * endpoint which creates an administrator safe: it can be reached exactly once, on an instance
     * nobody can yet sign into, and after that it is a closed door rather than a guarded one.
     */
    @WithTransaction
    public Uni<UserSummary> createFirstAdministrator(SetupRequest request) {
        return repository
                .countUsers()
                .flatMap(
                        count -> {
                            if (count > 0) {
                                return conflict("Keydra already has accounts");
                            }
                            AppUser user = new AppUser();
                            user.username = request.username();
                            user.displayName = request.displayName();
                            user.email = request.email();
                            user.provider = "local";
                            user.enabled = true;
                            user.passwordHash = hasher.hash(request.password());
                            return repository.save(user).flatMap(this::makeInstanceAdministrator);
                        });
    }

    private Uni<UserSummary> makeInstanceAdministrator(AppUser user) {
        return repository
                .roleByName(BuiltInRole.ADMIN.id())
                .flatMap(
                        role -> {
                            if (role == null) {
                                // The seeder writes these three at every start, so this means
                                // the application is not up yet rather than misconfigured.
                                return conflict("The built-in roles are not ready yet");
                            }
                            Grant grant = new Grant();
                            grant.subjectType = SubjectType.USER;
                            grant.subjectId = user.id;
                            grant.scopeType = ScopeType.INSTANCE;
                            grant.roleId = role.id;
                            grant.grantedBy = "setup";
                            return repository
                                    .save(grant)
                                    .map(ignored -> toSummary(user, List.of()));
                        });
    }

    /** Whether anybody exists yet, which is what decides the first-run path. */
    @WithSession
    public Uni<Boolean> hasAccounts() {
        return repository.countUsers().map(count -> count > 0);
    }

    // --- Groups ------------------------------------------------------------

    @WithSession
    public Uni<List<GroupSummary>> groups() {
        return names().flatMap(
                        names ->
                                repository
                                        .allMemberships()
                                        .flatMap(edges -> describeGroups(edges, names)));
    }

    private Uni<List<GroupSummary>> describeGroups(List<GroupMembership> edges, Names names) {
        return repository
                .allGroups()
                .map(
                        groups ->
                                groups.stream()
                                        .map(
                                                group ->
                                                        toSummary(
                                                                group,
                                                                edgesOf(edges, group.id),
                                                                names))
                                        .toList());
    }

    @WithTransaction
    public Uni<GroupSummary> createGroup(GroupRequest request) {
        UserGroup group = new UserGroup();
        group.name = request.name();
        group.description = request.description();
        return repository.save(group).map(saved -> toSummary(saved, List.of(), new Names()));
    }

    @WithTransaction
    public Uni<Boolean> deleteGroup(Long id) {
        return repository.deleteGroup(id);
    }

    /**
     * Puts somebody, or some group, into a group.
     *
     * <p>A group about to contain itself — however many steps away — is refused. Resolution walks
     * this graph, and while it is written not to hang on a cycle, a cycle still makes "who is in
     * this group" a question with no answer.
     */
    @WithTransaction
    public Uni<Void> addMember(Long groupId, MembershipRequest request) {
        if ((request.userId() == null) == (request.groupId() == null)) {
            return conflict("A membership is either a person or a group, not both and not neither");
        }
        if (groupId.equals(request.groupId())) {
            return conflict("A group cannot contain itself");
        }

        Uni<Boolean> acyclic =
                request.groupId() == null
                        ? Uni.createFrom().item(true)
                        : wouldStayAcyclic(groupId, request.groupId());

        return acyclic.flatMap(
                ok -> {
                    if (!ok) {
                        return conflict("That would put the group inside itself");
                    }
                    GroupMembership membership = new GroupMembership();
                    membership.groupId = groupId;
                    membership.memberUserId = request.userId();
                    membership.memberGroupId = request.groupId();
                    return repository.save(membership).replaceWithVoid();
                });
    }

    @WithTransaction
    public Uni<Boolean> removeMember(Long membershipId) {
        return repository.deleteMembership(membershipId);
    }

    /** Whether putting {@code child} inside {@code parent} leaves the graph without a cycle. */
    private Uni<Boolean> wouldStayAcyclic(Long parent, Long child) {
        return groupsAbove(Set.of(child), new HashSet<>(), 0).map(above -> !above.contains(parent));
    }

    /** Every group the given ones are inside, however indirectly. */
    private Uni<Set<Long>> groupsAbove(Set<Long> frontier, Set<Long> found, int depth) {
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
                            return groupsAbove(next, found, depth + 1);
                        });
    }

    // --- Server groups -----------------------------------------------------

    @WithSession
    public Uni<List<ServerGroupSummary>> serverGroups() {
        return repository.allServerGroupMembers().flatMap(this::describeServerGroups);
    }

    private Uni<List<ServerGroupSummary>> describeServerGroups(List<ServerGroupMember> members) {
        Map<Long, List<Long>> byGroup = new HashMap<>();
        members.forEach(
                member ->
                        byGroup.computeIfAbsent(member.groupId, ignored -> new ArrayList<>())
                                .add(member.connectionId));

        return repository
                .allServerGroups()
                .map(
                        groups ->
                                groups.stream()
                                        .map(
                                                group ->
                                                        toSummary(
                                                                group,
                                                                byGroup.getOrDefault(
                                                                        group.id, List.of())))
                                        .toList());
    }

    @WithTransaction
    public Uni<ServerGroupSummary> createServerGroup(ServerGroupRequest request) {
        ServerGroup group = new ServerGroup();
        group.name = request.name();
        group.description = request.description();
        group.parentId = request.parentId();
        return repository.save(group).map(saved -> toSummary(saved, List.of()));
    }

    @WithTransaction
    public Uni<Boolean> deleteServerGroup(Long id) {
        return repository.deleteServerGroup(id);
    }

    @WithTransaction
    public Uni<Void> addServerToGroup(Long groupId, Long connectionId) {
        return repository
                .serverGroupsOf(connectionId)
                .flatMap(
                        existing -> {
                            if (existing.stream()
                                    .anyMatch(member -> member.groupId.equals(groupId))) {
                                // Already there. Doing nothing beats a second row, which would
                                // double the target in every list drawn from this table.
                                return Uni.createFrom().voidItem();
                            }
                            ServerGroupMember member = new ServerGroupMember();
                            member.groupId = groupId;
                            member.connectionId = connectionId;
                            return repository.save(member).replaceWithVoid();
                        });
    }

    @WithTransaction
    public Uni<Boolean> removeServerFromGroup(Long groupId, Long connectionId) {
        return repository.removeServerFromGroup(groupId, connectionId);
    }

    // --- Roles -------------------------------------------------------------

    @WithSession
    public Uni<List<RoleSummary>> roles() {
        return repository
                .allRoles()
                .map(roles -> roles.stream().map(AuthzAdminService::toSummary).toList());
    }

    @WithTransaction
    public Uni<RoleSummary> createRole(RoleRequest request) {
        return repository
                .roleByName(request.name())
                .flatMap(
                        existing -> {
                            if (existing != null) {
                                return conflict(
                                        "A role called " + request.name() + " already exists");
                            }
                            RoleDefinition role = new RoleDefinition();
                            role.name = request.name();
                            role.description = request.description();
                            role.builtIn = false;
                            role.permissions = new HashSet<>(request.permissions());
                            return repository.save(role).map(AuthzAdminService::toSummary);
                        });
    }

    /**
     * Changes what a custom role carries.
     *
     * <p>A built-in one is refused. What those three mean is defined in code and rewritten at every
     * start, so an edit here would be undone by the next restart — which is worse than a refusal,
     * because it would appear to have worked.
     */
    @WithTransaction
    public Uni<RoleSummary> updateRole(Long id, RoleRequest request) {
        return repository
                .role(id)
                .flatMap(
                        role -> {
                            if (role == null) {
                                return conflict("No such role");
                            }
                            if (role.builtIn) {
                                return conflict("The built-in roles cannot be edited");
                            }
                            role.name = request.name();
                            role.description = request.description();
                            role.permissions = new HashSet<>(request.permissions());
                            return Uni.createFrom().item(toSummary(role));
                        });
    }

    @WithTransaction
    public Uni<Boolean> deleteRole(Long id) {
        return repository
                .role(id)
                .flatMap(
                        role -> {
                            if (role == null) {
                                return Uni.createFrom().item(false);
                            }
                            if (role.builtIn) {
                                return conflict("The built-in roles cannot be deleted");
                            }
                            return repository.deleteRole(id);
                        });
    }

    // --- Grants ------------------------------------------------------------

    @WithSession
    public Uni<List<GrantSummary>> grants() {
        return names().flatMap(
                        names -> repository.allGrants().map(grants -> describe(grants, names)));
    }

    @WithTransaction
    public Uni<GrantSummary> grant(GrantRequest request) {
        if ((request.scopeType() == ScopeType.INSTANCE) != (request.scopeId() == null)) {
            return conflict(
                    "A grant over Keydra itself names no scope, and a grant over anything else must"
                            + " name one");
        }
        Grant grant = new Grant();
        grant.subjectType = request.subjectType();
        grant.subjectId = request.subjectId();
        grant.scopeType = request.scopeType();
        grant.scopeId = request.scopeId();
        grant.roleId = request.roleId();
        grant.grantedBy = identity.isAnonymous() ? null : identity.getPrincipal().getName();

        return repository
                .save(grant)
                .flatMap(saved -> names().map(names -> describe(List.of(saved), names).get(0)));
    }

    @WithTransaction
    public Uni<Boolean> revoke(Long id) {
        return repository.deleteGrant(id);
    }

    // --- Turning ids into something a person can read ----------------------

    /**
     * Everything a grants page needs to show names instead of numbers.
     *
     * <p>Four small tables read once, rather than a join repeated per row. None of them is the sort
     * of table that grows: an installation with a thousand roles has a different problem.
     */
    private Uni<Names> names() {
        Names names = new Names();
        return repository
                .allUsers()
                .invoke(users -> users.forEach(user -> names.users.put(user.id, user.username)))
                .flatMap(ignored -> repository.allGroups())
                .invoke(groups -> groups.forEach(group -> names.groups.put(group.id, group.name)))
                .flatMap(ignored -> repository.allServerGroups())
                .invoke(
                        groups ->
                                groups.forEach(
                                        group -> names.serverGroups.put(group.id, group.name)))
                .flatMap(ignored -> repository.allRoles())
                .invoke(roles -> roles.forEach(role -> names.roles.put(role.id, role.name)))
                .replaceWith(names);
    }

    private static List<GroupMembership> edgesOf(List<GroupMembership> edges, Long groupId) {
        return edges.stream().filter(edge -> edge.groupId.equals(groupId)).toList();
    }

    private static Map<Long, List<String>> groupNamesByUser(
            List<GroupMembership> edges, Names names) {
        Map<Long, List<String>> byUser = new HashMap<>();
        edges.stream()
                .filter(edge -> edge.memberUserId != null)
                .forEach(
                        edge ->
                                byUser.computeIfAbsent(
                                                edge.memberUserId, ignored -> new ArrayList<>())
                                        .add(names.groups.getOrDefault(edge.groupId, "?")));
        return byUser;
    }

    private static List<GrantSummary> describe(List<Grant> grants, Names names) {
        return grants.stream()
                .map(
                        grant ->
                                new GrantSummary(
                                        grant.id,
                                        grant.subjectType,
                                        grant.subjectId,
                                        grant.subjectType == SubjectType.USER
                                                ? names.users.getOrDefault(grant.subjectId, "?")
                                                : names.groups.getOrDefault(grant.subjectId, "?"),
                                        grant.scopeType,
                                        grant.scopeId,
                                        scopeName(grant, names),
                                        grant.roleId,
                                        names.roles.getOrDefault(grant.roleId, "?"),
                                        grant.grantedAt,
                                        grant.grantedBy))
                .toList();
    }

    private static String scopeName(Grant grant, Names names) {
        return switch (grant.scopeType) {
            case INSTANCE -> "Keydra";
            case SERVER_GROUP -> names.serverGroups.getOrDefault(grant.scopeId, "?");
            // A connection's name lives in another domain's table. The id is what this domain
            // knows, and the page joins it against the catalog it has already loaded.
            case CONNECTION -> String.valueOf(grant.scopeId);
        };
    }

    private static boolean hasPassword(UserRequest request) {
        return request.password() != null && !request.password().isBlank();
    }

    private static <T> Uni<T> conflict(String message) {
        return Uni.createFrom().failure(new AuthzConflictException(message));
    }

    private static UserSummary toSummary(AppUser user, List<String> groups) {
        return new UserSummary(
                user.id,
                user.username,
                user.displayName,
                user.email,
                user.provider,
                user.enabled,
                user.passwordHash != null,
                user.lastSeenAt,
                groups);
    }

    private static GroupSummary toSummary(
            UserGroup group, List<GroupMembership> edges, Names names) {
        return new GroupSummary(
                group.id,
                group.name,
                group.description,
                group.managedBy,
                edges.stream()
                        .filter(edge -> edge.memberUserId != null)
                        .map(edge -> names.users.getOrDefault(edge.memberUserId, "?"))
                        .toList(),
                edges.stream()
                        .filter(edge -> edge.memberGroupId != null)
                        .map(edge -> names.groups.getOrDefault(edge.memberGroupId, "?"))
                        .toList());
    }

    private static ServerGroupSummary toSummary(ServerGroup group, List<Long> connectionIds) {
        return new ServerGroupSummary(
                group.id, group.name, group.description, group.parentId, connectionIds);
    }

    private static RoleSummary toSummary(RoleDefinition role) {
        return new RoleSummary(
                role.id, role.name, role.description, role.builtIn, Set.copyOf(role.permissions));
    }

    /** Ids to names, gathered once so a page of grants is not a page of numbers. */
    private static final class Names {
        private final Map<Long, String> users = new HashMap<>();
        private final Map<Long, String> groups = new HashMap<>();
        private final Map<Long, String> serverGroups = new HashMap<>();
        private final Map<Long, String> roles = new HashMap<>();
    }
}
