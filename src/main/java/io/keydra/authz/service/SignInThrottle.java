package io.keydra.authz.service;

import io.keydra.authz.exception.TooManyAttemptsException;
import io.keydra.authz.persistence.SignInAttemptRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Decides whether a password is worth checking.
 *
 * <p>Counted two ways, because the two attacks look nothing alike. Somebody guessing one person's
 * password makes many attempts against one name; somebody working through a list of usernames and
 * passwords they bought makes one attempt against each of many names, and a limit per name never
 * sees them. So one limit is per account and a second, looser one, is per network.
 *
 * <p>Both are counted over a rolling window rather than as a run of consecutive failures. A run
 * resets on any success, which is a thing an attacker with a list can arrange; a window does not
 * care what happened in between.
 *
 * <p>An account is never locked, only slowed. Locking one is a denial of service somebody can aim
 * at a named person by getting their password wrong on purpose, and the window expires on its own
 * without anybody having to be called.
 *
 * <p>The counting is in the database rather than in memory, which matters for the same reason
 * everything else since phase 17 does: with two Keydras and an in-memory counter, an attacker gets
 * both limits instead of one.
 */
@ApplicationScoped
public class SignInThrottle {

    private final SignInAttemptRepository attempts;
    private final Duration window;
    private final int perUsername;
    private final int perNetwork;
    private final boolean enabled;

    @Inject
    SignInThrottle(
            SignInAttemptRepository attempts,
            @ConfigProperty(name = "keydra.security.sign-in.window") Duration window,
            @ConfigProperty(name = "keydra.security.sign-in.max-failures-per-account")
                    int perUsername,
            @ConfigProperty(name = "keydra.security.sign-in.max-failures-per-network")
                    int perNetwork,
            @ConfigProperty(name = "keydra.security.sign-in.throttle-enabled") boolean enabled) {
        this.attempts = attempts;
        this.window = window;
        this.perUsername = perUsername;
        this.perNetwork = perNetwork;
        this.enabled = enabled;
    }

    /**
     * Fails with {@link TooManyAttemptsException} when this attempt should not be checked.
     *
     * <p>Answers before the password is read, so a refusal costs a count rather than a hash.
     */
    @WithSession
    public Uni<Void> guard(String username, ClientOrigin origin) {
        if (!enabled) {
            return Uni.createFrom().voidItem();
        }
        Instant since = Instant.now().minus(window);
        return attempts.failuresForUsernameSince(username, since)
                .flatMap(
                        forAccount ->
                                forAccount >= perUsername
                                        ? Uni.createFrom()
                                                .<Long>failure(new TooManyAttemptsException(window))
                                        : attempts.failuresForNetworkSince(origin.network(), since))
                .flatMap(
                        forNetwork ->
                                forNetwork >= perNetwork
                                        ? Uni.createFrom()
                                                .<Void>failure(new TooManyAttemptsException(window))
                                        : Uni.createFrom().voidItem());
    }
}
