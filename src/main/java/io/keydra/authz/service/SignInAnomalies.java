package io.keydra.authz.service;

import io.keydra.authz.entity.SignInAnomaly;
import io.keydra.authz.entity.SignInAttempt;
import io.keydra.authz.persistence.SignInAttemptRepository;
import io.keydra.authz.persistence.SignInAttemptRepository.Column;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * What is unusual about a sign-in that worked.
 *
 * <p>Everything here is about a correct password, which is what makes it worth doing: a wrong
 * password is refused and the throttle counts it, and a right one is let in. The only thing left to
 * look at is the shape around it — where it came from, what it was used on, and what came just
 * before it. A stolen password is a right password.
 *
 * <p>The first sign-in an account ever makes is exempt from all of it. Everything is new the first
 * time, and an alert that fires on every new colleague is an alert people learn to close. There is
 * nothing to compare against until there is something to compare against.
 *
 * <p>Read one query after another rather than at once, which is the rule everywhere a reactive
 * session is involved. It is several queries on a path taken a few times a day per person, which is
 * the right side of that trade.
 */
@ApplicationScoped
public class SignInAnomalies {

    private final SignInAttemptRepository attempts;
    private final Duration recent;
    private final int failuresBefore;
    private final int volumeFromOneSource;
    private final int accountsFromOneSource;
    private final Duration dormantAfter;
    private final Duration travelAllowance;

    @Inject
    SignInAnomalies(
            SignInAttemptRepository attempts,
            @ConfigProperty(name = "keydra.security.sign-in.window") Duration recent,
            @ConfigProperty(name = "keydra.security.sign-in.suspicious-failures-before")
                    int failuresBefore,
            @ConfigProperty(name = "keydra.security.sign-in.suspicious-volume")
                    int volumeFromOneSource,
            @ConfigProperty(name = "keydra.security.sign-in.suspicious-accounts")
                    int accountsFromOneSource,
            @ConfigProperty(name = "keydra.security.sign-in.dormant-after") Duration dormantAfter,
            @ConfigProperty(name = "keydra.security.sign-in.travel-allowance")
                    Duration travelAllowance) {
        this.attempts = attempts;
        this.recent = recent;
        this.failuresBefore = failuresBefore;
        this.volumeFromOneSource = volumeFromOneSource;
        this.accountsFromOneSource = accountsFromOneSource;
        this.dormantAfter = dormantAfter;
        this.travelAllowance = travelAllowance;
    }

    /** Everything worth saying about this sign-in, which is usually nothing. */
    @WithSession
    public Uni<Set<SignInAnomaly>> of(String username, ClientOrigin origin, String country) {
        Instant now = Instant.now();
        Instant since = now.minus(recent);
        Set<SignInAnomaly> noticed = EnumSet.noneOf(SignInAnomaly.class);

        return attempts.lastSuccessFor(username)
                .flatMap(
                        previous -> {
                            if (previous == null) {
                                // Nothing to be unlike yet.
                                return Uni.createFrom().item(noticed);
                            }
                            travel(previous, country, now, noticed);
                            dormancy(previous, now, noticed);
                            return novelty(username, origin, country, noticed)
                                    .flatMap(ignored -> pressure(username, origin, since, noticed));
                        });
    }

    /**
     * Two countries, too close together in time.
     *
     * <p>The allowance is deliberately generous — long enough that a flight is never flagged and
     * short enough that two continents in twenty minutes always is. It only ever fires when both
     * sign-ins have a country, so an instance without a geography database never sees it.
     */
    private void travel(
            SignInAttempt previous, String country, Instant now, Set<SignInAnomaly> noticed) {
        if (country == null || previous.country == null || country.equals(previous.country)) {
            return;
        }
        if (Duration.between(previous.at, now).compareTo(travelAllowance) < 0) {
            noticed.add(SignInAnomaly.IMPOSSIBLE_TRAVEL);
        }
    }

    private void dormancy(SignInAttempt previous, Instant now, Set<SignInAnomaly> noticed) {
        if (Duration.between(previous.at, now).compareTo(dormantAfter) > 0) {
            noticed.add(SignInAnomaly.DORMANT_ACCOUNT);
        }
    }

    /** Places and things this account has not been signed in from or with. */
    private Uni<Set<SignInAnomaly>> novelty(
            String username, ClientOrigin origin, String country, Set<SignInAnomaly> noticed) {
        return attempts.hasSucceededWith(username, Column.NETWORK, origin.network())
                .invoke(seen -> add(noticed, SignInAnomaly.NEW_NETWORK, !seen))
                .flatMap(
                        ignored ->
                                country == null
                                        ? Uni.createFrom().item(true)
                                        : attempts.hasSucceededWith(
                                                username, Column.COUNTRY, country))
                .invoke(seen -> add(noticed, SignInAnomaly.NEW_COUNTRY, !seen))
                .flatMap(
                        ignored ->
                                attempts.hasSucceededWith(
                                        username, Column.USER_AGENT, origin.userAgent()))
                .invoke(seen -> add(noticed, SignInAnomaly.NEW_DEVICE, !seen))
                .replaceWith(noticed);
    }

    /** What else has been happening around this sign-in. */
    private Uni<Set<SignInAnomaly>> pressure(
            String username, ClientOrigin origin, Instant since, Set<SignInAnomaly> noticed) {
        return attempts.failuresForUsernameSince(username, since)
                .invoke(
                        failures ->
                                add(
                                        noticed,
                                        SignInAnomaly.AFTER_REPEATED_FAILURES,
                                        failures >= failuresBefore))
                .flatMap(ignored -> attempts.attemptsFromNetworkSince(origin.network(), since))
                .invoke(
                        volume ->
                                add(
                                        noticed,
                                        SignInAnomaly.UNUSUAL_VOLUME,
                                        volume >= volumeFromOneSource))
                .flatMap(
                        ignored ->
                                attempts.accountsReachedFromNetworkSince(origin.network(), since))
                .invoke(
                        reached ->
                                add(
                                        noticed,
                                        SignInAnomaly.MANY_ACCOUNTS_ONE_SOURCE,
                                        reached >= accountsFromOneSource))
                .replaceWith(noticed);
    }

    private static void add(Set<SignInAnomaly> noticed, SignInAnomaly anomaly, boolean when) {
        if (when) {
            noticed.add(anomaly);
        }
    }
}
