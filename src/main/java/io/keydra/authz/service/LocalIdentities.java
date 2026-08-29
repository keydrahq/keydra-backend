package io.keydra.authz.service;

import io.keydra.authz.entity.AppUser;
import io.keydra.authz.entity.BuiltInRole;
import io.keydra.authz.persistence.AuthzRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.Set;

/**
 * Turns a local account into the identity a request carries.
 *
 * <p>The permission model has no notion of a role name attached to a person — a person holds roles
 * on scopes. But the older gate on Keydra's endpoints is {@code @RolesAllowed}, which knows three
 * names and nothing about scopes, so the session has to carry a summary of what its owner holds or
 * the coarse gate would refuse everybody the fine one would admit.
 *
 * <p>That summary is marked as derived, and {@link CallerPermissions} refuses to read it back as a
 * source of permissions. Reading it back would let the summary outrank what it summarises, and a
 * grant on one server group would become access to every server.
 */
@ApplicationScoped
public class LocalIdentities {

    /**
     * Marks an identity whose role names Keydra worked out itself.
     *
     * <p>As opposed to one whose roles arrived in somebody else's token, which is a fact about the
     * caller rather than a restatement of Keydra's own answer.
     */
    public static final String DERIVED_ROLES = "keydra.roles-are-derived";

    /**
     * Marks an identity that may do nothing but pair an authenticator.
     *
     * <p>Read by {@link CallerPermissions}, which answers the fine gate, as well as being the
     * reason the roles the coarse gate reads are empty. Both gates, from one fact, worked out once
     * — a second list of what a half-enrolled person may reach would be a list that is right on the
     * day it is written and wrong the next time somebody adds a resource.
     */
    public static final String OWES_A_FACTOR = "keydra.owes-a-second-factor";

    /** The provider name a locally managed account carries. */
    public static final String LOCAL = "local";

    private final AuthzRepository repository;
    private final PermissionResolver resolver;
    private final AuthzCache cache;
    private final SignInPolicies policies;

    @Inject
    LocalIdentities(
            AuthzRepository repository,
            PermissionResolver resolver,
            AuthzCache cache,
            SignInPolicies policies) {
        this.repository = repository;
        this.resolver = resolver;
        this.cache = cache;
        this.policies = policies;
    }

    /**
     * What is needed to check a password and, if it is right, to be somebody.
     *
     * <p>Gathered before the password is checked so that checking it needs no database at all —
     * hashing is slow on purpose and has to happen off the event loop, and reaching a reactive
     * session from another thread is the sort of thing that works until it does not.
     *
     * <p>An account that is disabled, that belongs to an identity provider, or that has no password
     * comes back with no stored hash. All three are "there is nothing here to check a password
     * against", and telling them apart at the login page would tell an attacker which usernames
     * exist.
     */
    @WithSession
    public Uni<Credentials> credentialsOf(String username) {
        return repository
                .userByUsername(username)
                .flatMap(
                        user -> {
                            if (user == null || !signInAble(user)) {
                                return Uni.createFrom()
                                        .item(
                                                new Credentials(
                                                        username, null, null, Set.of(), false));
                            }
                            return resolver.permissionsAnywhere(user.id)
                                    .flatMap(
                                            held ->
                                                    policies.restricts(user)
                                                            .map(
                                                                    owing ->
                                                                            new Credentials(
                                                                                    user.username,
                                                                                    user.id,
                                                                                    user.passwordHash,
                                                                                    owing
                                                                                            ? Set
                                                                                                    .of()
                                                                                            : BuiltInRole
                                                                                                    .summarise(
                                                                                                            held),
                                                                                    owing)));
                        });
    }

    /**
     * Builds the identity for somebody whose password has already been checked.
     *
     * <p>Called on every request that carries a cookie, which is why the answer is cached. Working
     * it out means the account, its groups, the groups above those, and every grant that reaches
     * any of them — a walk whose result is the same on the next request and the one after that.
     *
     * <p>The cache is cleared by anything that changes an account or a grant, so this stays what
     * phase 9 made it: proof of a past sign-in checked against a present account, rather than a
     * session carrying its own copy of what it was allowed to do.
     */
    @WithSession
    public Uni<SecurityIdentity> identityOf(String username) {
        return cache.identity(username, () -> resolveIdentity(username))
                .map(
                        known ->
                                known == null
                                        ? null
                                        : identity(
                                                username,
                                                known.owesAFactor() ? Set.of() : known.roles(),
                                                known.owesAFactor()));
    }

    /** The account and what its grants add up to, read from the database. */
    private Uni<AuthzCache.Identity> resolveIdentity(String username) {
        return repository
                .userByUsername(username)
                .flatMap(
                        user -> {
                            if (user == null || !user.enabled) {
                                return Uni.createFrom().nullItem();
                            }
                            return resolver.permissionsAnywhere(user.id)
                                    .flatMap(
                                            held ->
                                                    policies.restricts(user)
                                                            .map(
                                                                    owing ->
                                                                            new AuthzCache.Identity(
                                                                                    user.id,
                                                                                    BuiltInRole
                                                                                            .summarise(
                                                                                                    held),
                                                                                    owing)));
                        });
    }

    /**
     * The id behind a username, which every permission check needs before it can start.
     *
     * <p>Answered from the same cached entry as the identity, because it was read to build one.
     */
    @WithSession
    public Uni<Long> userIdOf(String username) {
        return cache.identity(username, () -> resolveIdentity(username))
                .map(known -> known == null ? null : known.userId());
    }

    /** The identity for credentials that have just been accepted. */
    public SecurityIdentity identity(Credentials credentials) {
        return identity(credentials.username(), credentials.roles(), credentials.owesAFactor());
    }

    /**
     * Notes that an account is in use.
     *
     * <p>Called from the request that restores a session — which is every request — so the caller
     * throttles it. Doing that here would not work: a call from within this class would not be
     * intercepted, and the transaction this needs would never be opened.
     */
    @WithTransaction
    public Uni<Void> recordSeen(String username, Instant at) {
        return repository
                .userByUsername(username)
                .invoke(
                        user -> {
                            if (user != null) {
                                user.lastSeenAt = at;
                            }
                        })
                .replaceWithVoid();
    }

    private static boolean signInAble(AppUser user) {
        return user.enabled && LOCAL.equals(user.provider) && user.passwordHash != null;
    }

    private static SecurityIdentity identity(String username, Set<String> roles, boolean owing) {
        return QuarkusSecurityIdentity.builder()
                .setPrincipal(new QuarkusPrincipal(username))
                .addRoles(roles)
                .addAttribute(DERIVED_ROLES, Boolean.TRUE)
                .addAttribute(OWES_A_FACTOR, owing)
                .build();
    }

    /**
     * Everything the password check needs, and everything it produces on success.
     *
     * @param storedHash null when there is nothing here to check a password against
     * @param roles the coarse role names this account's grants amount to
     */
    /**
     * What the password check needs, loaded before it runs.
     *
     * @param userId the account, or null when no account holds this name — which is a case this
     *     record carries deliberately rather than refusing, so a wrong username costs the same as a
     *     wrong password and the attempt can still be written down
     */
    public record Credentials(
            String username,
            Long userId,
            String storedHash,
            Set<String> roles,
            boolean owesAFactor) {}
}
