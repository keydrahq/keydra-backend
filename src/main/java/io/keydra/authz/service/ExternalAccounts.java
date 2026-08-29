package io.keydra.authz.service;

import io.keydra.authz.entity.AppUser;
import io.keydra.authz.entity.GroupMembership;
import io.keydra.authz.entity.IdentityProviderConfig;
import io.keydra.authz.entity.ProviderGroupMapping;
import io.keydra.authz.exception.SignInFailedException;
import io.keydra.authz.persistence.AuthzRepository;
import io.keydra.authz.service.ProviderSignIn.Claims;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Turning somebody a provider vouched for into an account Keydra knows.
 *
 * <p>Proving who you are is not being allowed in. A person arriving here for the first time gets an
 * account and holds nothing at all: every page is empty and every target invisible until an
 * administrator grants something, or until they land in a group that already has. That is the same
 * position a locally created account starts from, which is the point — where somebody came from
 * changes nothing about what they may do.
 */
@ApplicationScoped
@ChangesAccess
public class ExternalAccounts {

    private final AuthzRepository repository;

    @Inject
    ExternalAccounts(AuthzRepository repository) {
        this.repository = repository;
    }

    /**
     * The account for this person, created or adopted, with their groups brought up to date.
     *
     * @return the username the session will carry
     */
    @WithTransaction
    public Uni<String> accept(IdentityProviderConfig provider, Claims claims) {
        return repository
                .userByExternalId(provider.key, claims.subject())
                .flatMap(
                        known ->
                                known != null
                                        ? Uni.createFrom().item(known)
                                        : adopt(provider, claims))
                .flatMap(
                        user -> {
                            user.lastSeenAt = Instant.now();
                            // The provider is the source of truth for the parts it sends, and
                            // silent about the rest. A person who changes their name at work
                            // should not have to ask an administrator to change it here.
                            if (claims.email() != null) {
                                user.email = claims.email();
                            }
                            if (claims.name() != null) {
                                user.displayName = claims.name();
                            }
                            return synchroniseGroups(provider, user, claims)
                                    .replaceWith(user.username);
                        });
    }

    /**
     * The account somebody was given before they ever signed in, or a new one.
     *
     * <p>Adoption is by username within this provider, which is what makes an instance that does
     * not create accounts automatically usable at all: an administrator prepares the account, names
     * the provider it belongs to, and the first sign-in binds it to whatever that provider calls
     * them from then on.
     */
    private Uni<AppUser> adopt(IdentityProviderConfig provider, Claims claims) {
        return repository
                .userByUsername(claims.username())
                .flatMap(
                        sameName -> {
                            if (sameName != null) {
                                if (!provider.key.equals(sameName.provider)) {
                                    // Two people, one name. Renaming somebody silently is
                                    // worse than refusing: whichever of them we picked, the
                                    // other would find themselves signed in as a stranger.
                                    return Uni.createFrom()
                                            .failure(
                                                    new SignInFailedException(
                                                            "Somebody else here is already called "
                                                                    + claims.username()
                                                                    + ". An administrator has to"
                                                                    + " rename one of you."));
                                }
                                sameName.externalId = claims.subject();
                                return Uni.createFrom().item(sameName);
                            }
                            if (!provider.autoCreateUsers) {
                                return Uni.createFrom()
                                        .failure(
                                                new SignInFailedException(
                                                        "Keydra has no account for you. Ask an"
                                                                + " administrator to create one."));
                            }
                            AppUser user = new AppUser();
                            user.username = claims.username();
                            user.provider = provider.key;
                            user.externalId = claims.subject();
                            user.enabled = true;
                            // No password, ever: this account is signed into somewhere else,
                            // and one with a password here would be a second way in that the
                            // directory does not know about and cannot close.
                            user.passwordHash = null;
                            return repository.save(user);
                        });
    }

    /**
     * Replaces this person's membership of the mapped groups with what the claim says.
     *
     * <p>Only the mapped ones. A group nobody mapped was put there by an administrator, by hand,
     * for a reason the directory has no opinion about — emptying it on every sign-in would make
     * configuring Keydra impossible for anybody who signs in through a provider.
     */
    private Uni<Void> synchroniseGroups(
            IdentityProviderConfig provider, AppUser user, Claims claims) {
        return repository
                .mappingsOf(provider.id)
                .flatMap(
                        mappings -> {
                            if (mappings.isEmpty()) {
                                return Uni.createFrom().voidItem();
                            }
                            Set<Long> managed =
                                    mappings.stream()
                                            .map(mapping -> mapping.groupId)
                                            .collect(HashSet::new, HashSet::add, HashSet::addAll);
                            Set<Long> wanted = wanted(mappings, claims.groups());

                            return repository
                                    .membershipsOfUserIn(user.id, managed)
                                    .flatMap(existing -> reconcile(user, existing, wanted));
                        });
    }

    private static Set<Long> wanted(List<ProviderGroupMapping> mappings, List<String> claimed) {
        Set<String> sent = Set.copyOf(claimed);
        return mappings.stream()
                .filter(mapping -> sent.contains(mapping.claimValue))
                .map(mapping -> mapping.groupId)
                .collect(HashSet::new, HashSet::add, HashSet::addAll);
    }

    private Uni<Void> reconcile(AppUser user, List<GroupMembership> existing, Set<Long> wanted) {
        Set<Long> already = new HashSet<>();
        existing.forEach(edge -> already.add(edge.groupId));

        Uni<Void> work = Uni.createFrom().voidItem();

        for (GroupMembership edge : existing) {
            if (!wanted.contains(edge.groupId)) {
                Long id = edge.id;
                work = work.flatMap(ignored -> repository.deleteMembership(id).replaceWithVoid());
            }
        }
        for (Long groupId : wanted) {
            if (!already.contains(groupId)) {
                GroupMembership membership = new GroupMembership();
                membership.groupId = groupId;
                membership.memberUserId = user.id;
                work = work.flatMap(ignored -> repository.save(membership).replaceWithVoid());
            }
        }
        return work;
    }
}
