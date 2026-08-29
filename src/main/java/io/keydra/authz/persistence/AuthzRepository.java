package io.keydra.authz.persistence;

import io.keydra.authz.entity.AppUser;
import io.keydra.authz.entity.Grant;
import io.keydra.authz.entity.GroupMembership;
import io.keydra.authz.entity.IdentityProviderConfig;
import io.keydra.authz.entity.ProviderGroupMapping;
import io.keydra.authz.entity.RoleDefinition;
import io.keydra.authz.entity.ScopeType;
import io.keydra.authz.entity.ServerGroup;
import io.keydra.authz.entity.ServerGroupMember;
import io.keydra.authz.entity.SubjectType;
import io.keydra.authz.entity.UserGroup;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Collection;
import java.util.List;

/**
 * Reads and writes the authorization tables.
 *
 * <p>Queries rather than entity associations. A lazy association on the reactive session is a fetch
 * that has to be arranged rather than one that happens, and resolution walks two graphs — which is
 * a handful of deliberate queries whichever way it is written.
 */
@ApplicationScoped
public class AuthzRepository {

    // --- Users -------------------------------------------------------------

    public Uni<AppUser> userByUsername(String username) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from AppUser where username = :username",
                                                AppUser.class)
                                        .setParameter("username", username)
                                        .getSingleResultOrNull());
    }

    public Uni<List<AppUser>> allUsers() {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery("from AppUser order by username", AppUser.class)
                                        .getResultList());
    }

    public Uni<AppUser> save(AppUser user) {
        return Panache.getSession().flatMap(session -> session.persist(user).replaceWith(user));
    }

    public Uni<AppUser> user(Long id) {
        return Panache.getSession().flatMap(session -> session.find(AppUser.class, id));
    }

    /**
     * Removes somebody, and everything that pointed at them.
     *
     * <p>The memberships and grants go with the user rather than being left behind. A grant whose
     * subject no longer exists is not harmless: ids are reused by sequences on some databases, and
     * a row that says "user 7 is an administrator" outliving user 7 is how the next user 7 arrives
     * with powers nobody gave them.
     */
    public Uni<Boolean> deleteUser(Long id) {
        return execute("delete from GroupMembership where memberUserId = :id", id)
                .flatMap(
                        ignored ->
                                Panache.getSession()
                                        .flatMap(
                                                session ->
                                                        session.createQuery(
                                                                        "delete from Grant where"
                                                                            + " subjectType ="
                                                                            + " io.keydra.authz.entity.SubjectType.USER"
                                                                            + " and subjectId ="
                                                                            + " :id")
                                                                .setParameter("id", id)
                                                                .executeUpdate()))
                // Written out rather than left to the foreign key: dev and test build their schema
                // from the entities and never run the migration that declares the cascade, so a
                // row that only the database would have removed is a row that survives there.
                .flatMap(ignored -> execute("delete from UserPreference where userId = :id", id))
                .flatMap(ignored -> execute("delete from RecoveryCode where userId = :id", id))
                .flatMap(ignored -> execute("delete from SecondFactor where userId = :id", id))
                .flatMap(ignored -> execute("delete from AppUser where id = :id", id))
                .map(deleted -> deleted > 0);
    }

    // --- Groups ------------------------------------------------------------

    public Uni<List<UserGroup>> allGroups() {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery("from UserGroup order by name", UserGroup.class)
                                        .getResultList());
    }

    public Uni<UserGroup> save(UserGroup group) {
        return Panache.getSession().flatMap(session -> session.persist(group).replaceWith(group));
    }

    /** The groups these subjects are directly inside — one step of the subject graph. */
    public Uni<List<GroupMembership>> membershipsOfUser(Long userId) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from GroupMembership where memberUserId = :id",
                                                GroupMembership.class)
                                        .setParameter("id", userId)
                                        .getResultList());
    }

    /** The groups these groups are directly inside, for the next step of the walk. */
    public Uni<List<GroupMembership>> membershipsOfGroups(Collection<Long> groupIds) {
        if (groupIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from GroupMembership where memberGroupId in :ids",
                                                GroupMembership.class)
                                        .setParameter("ids", groupIds)
                                        .getResultList());
    }

    public Uni<GroupMembership> save(GroupMembership membership) {
        return Panache.getSession()
                .flatMap(session -> session.persist(membership).replaceWith(membership));
    }

    /** Every edge of the subject graph, for drawing it and for showing who is in what. */
    public Uni<List<GroupMembership>> allMemberships() {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery("from GroupMembership", GroupMembership.class)
                                        .getResultList());
    }

    public Uni<Boolean> deleteMembership(Long id) {
        return execute("delete from GroupMembership where id = :id", id)
                .map(deleted -> deleted > 0);
    }

    /** Removes a group, its edges in both directions, and the grants made to it. */
    public Uni<Boolean> deleteGroup(Long id) {
        return execute("delete from GroupMembership where groupId = :id", id)
                .flatMap(
                        ignored ->
                                execute(
                                        "delete from GroupMembership where memberGroupId = :id",
                                        id))
                .flatMap(
                        ignored ->
                                Panache.getSession()
                                        .flatMap(
                                                session ->
                                                        session.createQuery(
                                                                        "delete from Grant where"
                                                                            + " subjectType ="
                                                                            + " io.keydra.authz.entity.SubjectType.GROUP"
                                                                            + " and subjectId ="
                                                                            + " :id")
                                                                .setParameter("id", id)
                                                                .executeUpdate()))
                .flatMap(ignored -> execute("delete from UserGroup where id = :id", id))
                .map(deleted -> deleted > 0);
    }

    // --- Server groups -----------------------------------------------------

    public Uni<List<ServerGroup>> allServerGroups() {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from ServerGroup order by name", ServerGroup.class)
                                        .getResultList());
    }

    public Uni<ServerGroup> save(ServerGroup group) {
        return Panache.getSession().flatMap(session -> session.persist(group).replaceWith(group));
    }

    /** Which groups a target is in — the first step up the scope tree. */
    public Uni<List<ServerGroupMember>> serverGroupsOf(Long connectionId) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from ServerGroupMember where connectionId = :id",
                                                ServerGroupMember.class)
                                        .setParameter("id", connectionId)
                                        .getResultList());
    }

    public Uni<ServerGroupMember> save(ServerGroupMember member) {
        return Panache.getSession().flatMap(session -> session.persist(member).replaceWith(member));
    }

    public Uni<Boolean> removeServerFromGroup(Long groupId, Long connectionId) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "delete from ServerGroupMember where groupId ="
                                                    + " :groupId and connectionId = :connectionId")
                                        .setParameter("groupId", groupId)
                                        .setParameter("connectionId", connectionId)
                                        .executeUpdate())
                .map(deleted -> deleted > 0);
    }

    /** Removes a server group, its members, its children's link to it, and grants on it. */
    public Uni<Boolean> deleteServerGroup(Long id) {
        return execute("delete from ServerGroupMember where groupId = :id", id)
                .flatMap(
                        ignored ->
                                execute(
                                        "update ServerGroup set parentId = null where parentId ="
                                                + " :id",
                                        id))
                .flatMap(
                        ignored ->
                                Panache.getSession()
                                        .flatMap(
                                                session ->
                                                        session.createQuery(
                                                                        "delete from Grant where"
                                                                            + " scopeType ="
                                                                            + " io.keydra.authz.entity.ScopeType.SERVER_GROUP"
                                                                            + " and scopeId = :id")
                                                                .setParameter("id", id)
                                                                .executeUpdate()))
                .flatMap(ignored -> execute("delete from ServerGroup where id = :id", id))
                .map(deleted -> deleted > 0);
    }

    /** Every membership, so a page can show which target sits in which group. */
    public Uni<List<ServerGroupMember>> allServerGroupMembers() {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from ServerGroupMember", ServerGroupMember.class)
                                        .getResultList());
    }

    /** Every connection in these groups, for deciding what a subject can see. */
    public Uni<List<ServerGroupMember>> membersOfServerGroups(Collection<Long> groupIds) {
        if (groupIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from ServerGroupMember where groupId in :ids",
                                                ServerGroupMember.class)
                                        .setParameter("ids", groupIds)
                                        .getResultList());
    }

    // --- Roles and grants --------------------------------------------------

    public Uni<List<RoleDefinition>> allRoles() {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from RoleDefinition order by name",
                                                RoleDefinition.class)
                                        .getResultList());
    }

    /**
     * Opens a session when there is not one already.
     *
     * <p>Every other method here is called from a service that has one. This one is also read
     * directly — by the seeder at startup and by callers checking what a role carries — and Quarkus
     * reuses the current session when there is one, so the annotation costs nothing.
     */
    @WithSession
    public Uni<RoleDefinition> roleByName(String name) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from RoleDefinition where name = :name",
                                                RoleDefinition.class)
                                        .setParameter("name", name)
                                        .getSingleResultOrNull());
    }

    public Uni<RoleDefinition> save(RoleDefinition role) {
        return Panache.getSession().flatMap(session -> session.persist(role).replaceWith(role));
    }

    public Uni<RoleDefinition> role(Long id) {
        return Panache.getSession().flatMap(session -> session.find(RoleDefinition.class, id));
    }

    /** Removes a custom role and every grant that named it. */
    public Uni<Boolean> deleteRole(Long id) {
        return execute("delete from Grant where roleId = :id", id)
                .flatMap(ignored -> execute("delete from RoleDefinition where id = :id", id))
                .map(deleted -> deleted > 0);
    }

    /** How many grants name this role, so deleting one can say what it would take with it. */
    public Uni<Long> grantsUsingRole(Long roleId) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "select count(g) from Grant g where g.roleId = :id",
                                                Long.class)
                                        .setParameter("id", roleId)
                                        .getSingleResult());
    }

    /**
     * Every grant made to any of these subjects.
     *
     * <p>All of them at once rather than per scope: the caller already knows which scopes reach the
     * target it is asking about, and one query is one round trip.
     */
    public Uni<List<Grant>> grantsFor(SubjectType type, Collection<Long> subjectIds) {
        if (subjectIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from Grant where subjectType = :type"
                                                        + " and subjectId in :ids",
                                                Grant.class)
                                        .setParameter("type", type)
                                        .setParameter("ids", subjectIds)
                                        .getResultList());
    }

    public Uni<List<Grant>> allGrants() {
        return Panache.getSession()
                .flatMap(session -> session.createQuery("from Grant", Grant.class).getResultList());
    }

    public Uni<Grant> save(Grant grant) {
        return Panache.getSession().flatMap(session -> session.persist(grant).replaceWith(grant));
    }

    public Uni<Boolean> deleteGrant(Long id) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery("delete from Grant where id = :id")
                                        .setParameter("id", id)
                                        .executeUpdate()
                                        .map(deleted -> deleted > 0));
    }

    /** Where a scope sits, so a grant on a parent can be seen to reach a child. */
    public Uni<ServerGroup> serverGroup(Long id) {
        return Panache.getSession().flatMap(session -> session.find(ServerGroup.class, id));
    }

    /** Whether anything at all has been configured, which decides the first-run path. */
    public Uni<Long> countUsers() {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery("select count(u) from AppUser u", Long.class)
                                        .getSingleResult());
    }

    // --- Identity providers -------------------------------------------------

    public Uni<List<IdentityProviderConfig>> allProviders() {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from IdentityProviderConfig"
                                                        + " order by sortOrder, displayName",
                                                IdentityProviderConfig.class)
                                        .getResultList());
    }

    public Uni<IdentityProviderConfig> providerByKey(String key) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from IdentityProviderConfig where key = :key",
                                                IdentityProviderConfig.class)
                                        .setParameter("key", key)
                                        .getSingleResultOrNull());
    }

    /**
     * One provider, read in a session of its own.
     *
     * <p>For the caller that is about to go out to that provider's server: an outbound fetch takes
     * as long as somebody else makes it take, and a session held across it is a database connection
     * held across it. The same rule {@code BackupRepository.forUse} states.
     */
    @WithSession
    public Uni<IdentityProviderConfig> providerForUse(Long id) {
        return provider(id);
    }

    public Uni<IdentityProviderConfig> provider(Long id) {
        return Panache.getSession()
                .flatMap(session -> session.find(IdentityProviderConfig.class, id));
    }

    public Uni<IdentityProviderConfig> save(IdentityProviderConfig provider) {
        return Panache.getSession()
                .flatMap(session -> session.persist(provider).replaceWith(provider));
    }

    /** Removes a provider and its group mappings; the accounts it created stay. */
    public Uni<Boolean> deleteProvider(Long id) {
        return execute("delete from ProviderGroupMapping where providerId = :id", id)
                .flatMap(
                        ignored -> execute("delete from IdentityProviderConfig where id = :id", id))
                .map(deleted -> deleted > 0);
    }

    public Uni<List<ProviderGroupMapping>> mappingsOf(Long providerId) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from ProviderGroupMapping where providerId = :id",
                                                ProviderGroupMapping.class)
                                        .setParameter("id", providerId)
                                        .getResultList());
    }

    public Uni<ProviderGroupMapping> save(ProviderGroupMapping mapping) {
        return Panache.getSession()
                .flatMap(session -> session.persist(mapping).replaceWith(mapping));
    }

    public Uni<Boolean> deleteMapping(Long id) {
        return execute("delete from ProviderGroupMapping where id = :id", id)
                .map(deleted -> deleted > 0);
    }

    /** Somebody a provider has sent before, found by what the provider calls them. */
    public Uni<AppUser> userByExternalId(String provider, String externalId) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from AppUser where provider = :provider"
                                                        + " and externalId = :externalId",
                                                AppUser.class)
                                        .setParameter("provider", provider)
                                        .setParameter("externalId", externalId)
                                        .getSingleResultOrNull());
    }

    /** The memberships this user has in any of these groups, for replacing them wholesale. */
    public Uni<List<GroupMembership>> membershipsOfUserIn(Long userId, Collection<Long> groupIds) {
        if (groupIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from GroupMembership where memberUserId = :id"
                                                        + " and groupId in :groups",
                                                GroupMembership.class)
                                        .setParameter("id", userId)
                                        .setParameter("groups", groupIds)
                                        .getResultList());
    }

    /** Convenience for the scope constant, so callers do not repeat the enum. */
    public static ScopeType instance() {
        return ScopeType.INSTANCE;
    }

    /** One statement taking one id, which is most of the deletions above. */
    private static Uni<Integer> execute(String query, Long id) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(query).setParameter("id", id).executeUpdate());
    }
}
