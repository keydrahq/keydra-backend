package io.keydra.authz.service;

import io.keydra.authz.dto.AuthzDtos.SignInPolicyState;
import io.keydra.authz.entity.AppUser;
import io.keydra.authz.exception.AuthzConflictException;
import io.keydra.authz.persistence.SecondFactorRepository;
import io.keydra.authz.persistence.SignInPolicyRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;

/**
 * What this installation asks of everybody who signs in.
 *
 * <p>One thing so far, and it is the one phase 38 left out: whether a local account has to have a
 * confirmed authenticator before it may do anything at all.
 *
 * <p><b>The guard is the reason this is a row rather than a configuration property.</b> Nobody may
 * require a second factor without having one, and that is checked at the moment the switch is
 * flipped, by somebody who is looking at the page. A property is set in a manifest by somebody who
 * cannot see who has enrolled, takes effect at a restart nobody is watching, and locks out every
 * account that had not — usually including the account of whoever set it, because the person who
 * decides a policy is rarely the first to comply with it.
 *
 * <p>Local accounts only. An account that signs in through an identity provider proved who it was
 * somewhere else; whether that somewhere else asked for a second factor is not Keydra's to know,
 * and asking again here would be asking somebody to carry a credential for a door they do not use —
 * they have no Keydra password either. The provider is the authority on how its people prove who
 * they are.
 */
@ApplicationScoped
public class SignInPolicies {

    private final SignInPolicyRepository repository;
    private final SecondFactorRepository factors;

    @Inject
    SignInPolicies(SignInPolicyRepository repository, SecondFactorRepository factors) {
        this.repository = repository;
        this.factors = factors;
    }

    /**
     * Whether this account may do nothing but enrol.
     *
     * <p>Asked where an identity is built, and cached with it — so the two reads here happen once
     * per cache entry rather than once per request. Three things have to be true together, and the
     * order is cheapest first only by accident: the policy is on, the account is one the policy
     * reaches, and it has no confirmed factor.
     */
    @WithSession
    public Uni<Boolean> restricts(AppUser user) {
        if (user == null || !LocalIdentities.LOCAL.equals(user.provider)) {
            return Uni.createFrom().item(false);
        }
        return secondFactorRequired()
                .flatMap(
                        required ->
                                !required
                                        ? Uni.createFrom().item(false)
                                        : factors.forUser(user.id)
                                                .map(
                                                        factor ->
                                                                factor == null
                                                                        || !factor.isConfirmed()));
    }

    /** Whether a second factor is required at all, which is most of what anything asks. */
    @WithSession
    public Uni<Boolean> secondFactorRequired() {
        return repository.read().map(row -> row != null && row.secondFactorRequired);
    }

    /**
     * The policy as a page shows it: what is asked, who last asked it, and how many it reaches.
     *
     * <p>The count is the part that earns the page. The question anybody sensible asks before
     * turning this on is "how many people am I about to shut out this morning", and a switch that
     * cannot answer that is a switch pressed at the wrong time of day.
     */
    @WithSession
    public Uni<SignInPolicyState> state() {
        return repository
                .read()
                .flatMap(
                        row ->
                                repository
                                        .owingAFactor()
                                        .map(
                                                owing ->
                                                        new SignInPolicyState(
                                                                row != null
                                                                        && row.secondFactorRequired,
                                                                row == null ? null : row.changedAt,
                                                                row == null ? null : row.changedBy,
                                                                owing)));
    }

    /**
     * Turns the requirement on or off.
     *
     * <p>Turning it on is refused unless the caller has a confirmed factor of their own. That is
     * the single check that separates this from a way to lose an installation: without it the act
     * of requiring a factor strips the roles of the account doing the requiring, including the
     * permission to undo it.
     *
     * <p>Turning it off has no guard, deliberately. Every administrator who could turn it on has a
     * factor, so there is always somebody who can turn it off — and a guard on the way out would be
     * a guard on the only way out.
     */
    @WithTransaction
    @ChangesAccess
    public Uni<SignInPolicyState> requireSecondFactor(
            boolean required, Long callerUserId, String callerName) {
        return guard(required, callerUserId)
                .flatMap(ignored -> repository.write(required, callerName, Instant.now()))
                .flatMap(written -> state());
    }

    private Uni<Void> guard(boolean required, Long callerUserId) {
        if (!required) {
            return Uni.createFrom().voidItem();
        }
        return factors.forUser(callerUserId)
                .flatMap(
                        factor ->
                                factor != null && factor.isConfirmed()
                                        ? Uni.createFrom().voidItem()
                                        : Uni.createFrom()
                                                .failure(
                                                        new AuthzConflictException(
                                                                "Requiring a second factor would"
                                                                    + " lock you out. Pair an"
                                                                    + " authenticator with your own"
                                                                    + " account first.")));
    }
}
