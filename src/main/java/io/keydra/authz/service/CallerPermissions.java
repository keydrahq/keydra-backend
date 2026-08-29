package io.keydra.authz.service;

import io.keydra.authz.entity.BuiltInRole;
import io.keydra.authz.entity.Permission;
import io.keydra.authz.persistence.AuthzRepository;
import io.keydra.security.service.SecuritySettings;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * What the person making this request may do.
 *
 * <p>Two sources, and the order matters. A caller whose roles arrive in a token holds whatever
 * those built-in roles carry — which is how every deployment that exists today keeps working
 * without anybody configuring anything. A caller Keydra knows as a user holds whatever has been
 * granted to them, which is the model this phase is for. A caller who is both holds the union: an
 * administrator by token does not lose their access the moment somebody creates a user row.
 *
 * <p>With enforcement switched off, everything is permitted. That is not a gap: the deployment has
 * said it is not checking, and an application that then refused would mean "nothing works" rather
 * than "nobody is checked".
 */
@ApplicationScoped
public class CallerPermissions {

    private final SecurityIdentity identity;
    private final SecuritySettings settings;
    private final PermissionResolver resolver;
    private final AuthzRepository repository;
    private final LocalIdentities identities;
    private final AuthzCache cache;

    @Inject
    CallerPermissions(
            SecurityIdentity identity,
            SecuritySettings settings,
            PermissionResolver resolver,
            AuthzRepository repository,
            LocalIdentities identities,
            AuthzCache cache) {
        this.identity = identity;
        this.settings = settings;
        this.resolver = resolver;
        this.repository = repository;
        this.identities = identities;
        this.cache = cache;
    }

    /** Everything the caller may do to this target, or to Keydra itself when null. */
    @WithSession
    public Uni<Set<Permission>> forConnection(Long connectionId) {
        if (!settings.enabled()) {
            return Uni.createFrom().item(EnumSet.allOf(Permission.class));
        }
        if (owesAFactor()) {
            // This installation requires a second factor and this account has not paired one.
            // Nothing, rather than a list of exceptions: what stays reachable is what was already
            // reachable to somebody holding no grants at all — your own factor, your own sessions,
            // your own preferences, and who you are — and that is exactly the surface somebody in
            // this state needs. A list here would be a list somebody forgets to add to.
            return Uni.createFrom().item(EnumSet.noneOf(Permission.class));
        }

        Set<Permission> fromToken = fromRoleClaims();
        return granted(connectionId)
                .map(
                        fromGrants -> {
                            EnumSet<Permission> both = EnumSet.noneOf(Permission.class);
                            both.addAll(fromToken);
                            both.addAll(fromGrants);
                            return both;
                        });
    }

    /** Whether the caller holds one particular permission here. */
    public Uni<Boolean> holds(Permission permission, Long connectionId) {
        return forConnection(connectionId).map(held -> held.contains(permission));
    }

    /**
     * Which of these targets the caller holds this permission on.
     *
     * <p>Exists because asking for a connection-level permission without naming a connection is a
     * question with no good answer. {@code forConnection(null)} resolves only the grants made at
     * instance level, so a permission granted on a target — or on a group of them, which is the
     * normal way — is not in that set; a fleet-wide operation guarded that way refused everybody
     * who was not carrying a built-in role. Asking per target is the same question the per-target
     * operations ask, and it gives the same answer.
     *
     * <p>One after another rather than at once, for the reason every loop like this one in Keydra
     * is sequential: a reactive session runs one query at a time, and issuing them concurrently
     * produces "session is currently executing another query" instead of a faster answer. Each is a
     * cache lookup in the ordinary case.
     */
    @WithSession
    public Uni<List<Long>> holdingAll(Permission permission, List<Long> candidates) {
        Uni<List<Long>> resolved = Uni.createFrom().item(new ArrayList<Long>());
        for (Long id : candidates) {
            resolved =
                    resolved.flatMap(
                            soFar ->
                                    holds(permission, id)
                                            .map(
                                                    held -> {
                                                        if (held) {
                                                            soFar.add(id);
                                                        }
                                                        return soFar;
                                                    }));
        }
        return resolved.map(List::copyOf);
    }

    /**
     * Which of these targets the caller can see.
     *
     * <p>A caller holding a built-in role by token sees everything, because that is what those
     * roles have always meant and narrowing them would break deployments that never asked for
     * groups.
     */
    /**
     * Whether this caller's roles came from a token rather than from a grant.
     *
     * <p>Those see every target, because that is what a built-in role has always meant here and
     * narrowing it would break deployments that never asked for groups. Public because the socket
     * audience has to remember the answer: it is asked during a handshake and needed afterwards,
     * when the request that carried the identity is long gone.
     */
    public boolean holdsRoleClaims() {
        return !fromRoleClaims().isEmpty();
    }

    /**
     * Whether the caller owes this installation a second factor.
     *
     * <p>Off the identity rather than out of the database: it was worked out where the identity
     * was, and an identity that arrived any other way — a token, a provider — carries no such
     * attribute and is not what the policy is about.
     */
    private boolean owesAFactor() {
        return Boolean.TRUE.equals(identity.getAttribute(LocalIdentities.OWES_A_FACTOR));
    }

    /** Whether enforcement is on at all. With it off, everybody sees everything. */
    public boolean enforcing() {
        return settings.enabled();
    }

    @WithSession
    public Uni<Set<Long>> visible(List<Long> candidates) {
        if (!settings.enabled() || !fromRoleClaims().isEmpty()) {
            return Uni.createFrom().item(Set.copyOf(candidates));
        }
        return currentUserId().flatMap(userId -> resolver.visibleConnections(userId, candidates));
    }

    /**
     * The permissions carried by whatever built-in roles the token names.
     *
     * <p>Empty for an identity Keydra authenticated itself. The role names on one of those are a
     * summary of the grants, put there so the coarse {@code @RolesAllowed} gates pass; reading them
     * back here would let the summary outrank what it summarises, and a grant on one server group
     * would become access to every server.
     */
    private Set<Permission> fromRoleClaims() {
        EnumSet<Permission> permissions = EnumSet.noneOf(Permission.class);
        if (identity.getAttribute(LocalIdentities.DERIVED_ROLES) != null) {
            return permissions;
        }
        identity.getRoles().stream()
                .map(BuiltInRole::byId)
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .forEach(role -> permissions.addAll(role.permissions()));
        return permissions;
    }

    private Uni<Set<Permission>> granted(Long connectionId) {
        return currentUserId()
                .flatMap(
                        userId ->
                                userId == null
                                        ? Uni.createFrom().item(Set.of())
                                        : cache.permissions(
                                                userId,
                                                connectionId,
                                                () ->
                                                        resolver.permissionsFor(
                                                                userId, connectionId)));
    }

    /**
     * The user row for whoever is asking, if there is one.
     *
     * <p>Null rather than an error when there is not: an identity from a provider Keydra has not
     * seen before is a stranger, and a stranger holds nothing rather than failing.
     */
    /**
     * The id behind the caller, asked of the same cached entry the identity came from.
     *
     * <p>Every permission check starts here, so before this was cached every request turned a name
     * into an id by going to the database — for a mapping that changes when somebody is renamed,
     * which is to say almost never.
     *
     * <p>Public because things other than permission checks need to know whose request this is: a
     * console history belongs to a person rather than to a target, and an audit entry names one.
     * Null for a caller Keydra has no account for, which includes an instance with enforcement off
     * — callers must handle that rather than assume there is always somebody.
     */
    @WithSession
    public Uni<Long> currentUserId() {
        if (identity.isAnonymous() || identity.getPrincipal() == null) {
            return Uni.createFrom().nullItem();
        }
        return identities.userIdOf(identity.getPrincipal().getName());
    }
}
