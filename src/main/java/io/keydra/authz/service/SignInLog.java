package io.keydra.authz.service;

import io.keydra.authz.entity.SignInAnomaly;
import io.keydra.authz.entity.SignInAttempt;
import io.keydra.authz.entity.SignInOutcome;
import io.keydra.authz.persistence.SignInAttemptRepository;
import io.keydra.events.dto.NotificationCategory;
import io.keydra.events.service.NotificationHub;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * Writes down every attempt to sign in, and says when one looks wrong.
 *
 * <p>The single place either thing happens, whichever way somebody came in — the password form and
 * an identity provider both end here. That is why it is a bean of its own rather than something the
 * identity provider does on the side: two recorders would eventually disagree about what counts,
 * and the throttle counts what is recorded.
 *
 * <p>Nothing here is allowed to fail a sign-in. A password that is right is right whether or not
 * the row describing it could be written, and an instance whose history table is unavailable should
 * let people work rather than lock everybody out. Failures are logged and swallowed, which is the
 * opposite of the rule almost everywhere else in Keydra and is deliberate.
 */
@ApplicationScoped
public class SignInLog {

    private static final Logger LOG = Logger.getLogger(SignInLog.class);

    /** The method recorded for the password form, as against the key of a provider. */
    public static final String PASSWORD = "password";

    private final SignInAttemptRepository attempts;
    private final SignInThrottle throttle;
    private final SignInAnomalies anomalies;
    private final SignInGeography geography;
    private final NotificationHub hub;
    private final LocalIdentities identities;

    @Inject
    SignInLog(
            SignInAttemptRepository attempts,
            SignInThrottle throttle,
            SignInAnomalies anomalies,
            SignInGeography geography,
            NotificationHub hub,
            LocalIdentities identities) {
        this.attempts = attempts;
        this.throttle = throttle;
        this.anomalies = anomalies;
        this.geography = geography;
        this.hub = hub;
        this.identities = identities;
    }

    /**
     * Refuses before the password is checked when too many have just been tried.
     *
     * <p>Fails rather than answering false, because the caller's only correct response to a refusal
     * is to stop, and a boolean is a thing somebody forgets to read.
     */
    public Uni<Void> guard(String username, ClientOrigin origin) {
        return throttle.guard(username, origin);
    }

    /**
     * The same, for a sign-in that arrived through an identity provider.
     *
     * <p>The provider path has a username and no account id — the account was found or created
     * inside the exchange — so the id is looked up here rather than threaded back out through it.
     */
    public Uni<Void> succeeded(String username, ClientOrigin origin, String method) {
        return identities
                .userIdOf(username)
                .onFailure()
                .recoverWithItem((Long) null)
                .flatMap(userId -> succeeded(username, userId, origin, method));
    }

    /** Records a refusal. Never fails. */
    @WithTransaction
    public Uni<Void> failed(
            String username,
            Long userId,
            ClientOrigin origin,
            SignInOutcome outcome,
            String method) {
        SignInAttempt attempt = row(username, userId, origin, outcome, method);
        return attempts.save(attempt)
                .replaceWithVoid()
                .onFailure()
                .recoverWithItem(
                        unwritable -> {
                            LOG.debugf(
                                    unwritable,
                                    "Could not record a refused sign-in for %s",
                                    method);
                            return null;
                        });
    }

    /**
     * Records a sign-in that worked, and looks at what is unusual about it.
     *
     * <p>The looking happens on the way in rather than later, because half of the comparison is
     * "what came just before this" and the row about to be written is the thing that would change
     * the answer. Work out what is unusual, then write the row that makes it usual.
     */
    @WithTransaction
    public Uni<Void> succeeded(String username, Long userId, ClientOrigin origin, String method) {
        String country = geography.countryOf(origin.address()).orElse(null);
        return anomalies
                .of(username, origin, country)
                .flatMap(
                        noticed -> {
                            SignInAttempt attempt =
                                    row(username, userId, origin, SignInOutcome.SUCCEEDED, method);
                            attempt.country = country;
                            attempt.anomalySet(noticed);
                            return attempts.save(attempt)
                                    .invoke(saved -> announce(saved, noticed))
                                    .replaceWithVoid();
                        })
                .onFailure()
                .recoverWithItem(
                        unwritable -> {
                            LOG.debugf(unwritable, "Could not record a sign-in for %s", method);
                            return null;
                        });
    }

    /**
     * Tells whoever is watching, when there is something to tell.
     *
     * <p>Carries no connection, because this is about Keydra rather than about a target — the hub
     * sends an untagged envelope to everybody who may be signed in, and what is in it is a username
     * and where it came from, which is what somebody would need to act on it.
     */
    private void announce(SignInAttempt attempt, Set<SignInAnomaly> noticed) {
        if (noticed.isEmpty()) {
            return;
        }
        LOG.warnf(
                "Sign-in for %s from %s was flagged: %s",
                attempt.username,
                attempt.network == null ? "an unknown network" : attempt.network,
                attempt.anomalies);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", attempt.username);
        payload.put("network", attempt.network);
        payload.put("country", attempt.country);
        payload.put("at", attempt.at);
        payload.put("anomalies", noticed.stream().map(Enum::name).toList());
        hub.broadcast(NotificationCategory.SIGN_IN_FLAGGED, payload);
    }

    private SignInAttempt row(
            String username,
            Long userId,
            ClientOrigin origin,
            SignInOutcome outcome,
            String method) {
        SignInAttempt attempt = new SignInAttempt();
        // Bounded rather than trusted: the column is 200 and what arrives is whatever was typed
        // into a form field somebody else's browser rendered.
        attempt.username = username == null ? "" : trim(username, 200);
        attempt.userId = userId;
        attempt.outcome = outcome;
        attempt.method = method;
        attempt.network = origin.network();
        attempt.userAgent = origin.userAgent();
        return attempt;
    }

    private static String trim(String value, int length) {
        return value.length() > length ? value.substring(0, length) : value;
    }
}
