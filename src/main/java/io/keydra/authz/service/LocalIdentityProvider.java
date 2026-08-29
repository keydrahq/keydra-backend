package io.keydra.authz.service;

import io.keydra.authz.entity.SignInOutcome;
import io.keydra.authz.exception.TooManyAttemptsException;
import io.keydra.authz.service.LocalIdentities.Credentials;
import io.keydra.common.vertx.OwnContext;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.IdentityProvider;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.request.UsernamePasswordAuthenticationRequest;
import io.quarkus.vertx.http.runtime.security.HttpSecurityUtils;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Checks a username and password against a local account.
 *
 * <p>This is where the login form ends up: Quarkus' form authentication turns the posted fields
 * into a request, and something has to say whether it is right. Nothing else in Keydra reads a
 * password, and nothing at all reads a stored hash back out.
 *
 * <p>Everything the check needs is loaded first, so the check itself touches no database. Argon2 is
 * slow on purpose and therefore runs on a worker thread, and a reactive session belongs to the
 * event loop it was opened on — keeping the two apart is what stops a login from being either a
 * stall or a race.
 *
 * <p>Three things happen around the check that are not the check. Before it, the throttle is asked
 * whether this attempt is worth making; a refusal costs a count rather than 19 MiB of memory and a
 * second of a core, which is what makes the deliberate slowness of Argon2 a defence rather than a
 * way to exhaust the server. After it, the attempt is written down whichever way it went — and on
 * the way in, a sign-in that worked is compared against the ones before it, because a stolen
 * password is a correct password and the shape around it is all there is left to look at.
 *
 * <p>The writing happens on a context of its own and the answer does not wait for it. Somebody
 * signing in should not be held up by a history table, and a history table that cannot be written
 * should not stop them signing in.
 */
@ApplicationScoped
public class LocalIdentityProvider
        implements IdentityProvider<UsernamePasswordAuthenticationRequest> {

    private static final Logger LOG = Logger.getLogger(LocalIdentityProvider.class);

    /** The form field the login page adds when it has been told a code is wanted. */
    public static final String SECOND_FACTOR_FIELD = "code";

    /** What the refusal carries when the password was right and the code was not. */
    public static final String SECOND_FACTOR_HEADER = "X-Keydra-Second-Factor";

    /**
     * A hash of nothing anybody knows.
     *
     * <p>Verified against when the username does not exist, so a wrong username costs the same time
     * as a wrong password. Without it the login page answers "is this a real account?" in
     * milliseconds — which is the first half of guessing a password, handed over for free.
     */
    private final String decoy;

    private final LocalIdentities identities;
    private final PasswordHasher hasher;
    private final SignInLog log;
    private final SecondFactors secondFactors;
    private final Vertx vertx;

    @Inject
    LocalIdentityProvider(
            LocalIdentities identities,
            PasswordHasher hasher,
            SignInLog log,
            SecondFactors secondFactors,
            Vertx vertx) {
        this.identities = identities;
        this.hasher = hasher;
        this.log = log;
        this.secondFactors = secondFactors;
        this.vertx = vertx;
        this.decoy = hasher.hash("keydra-has-no-such-account");
    }

    @Override
    public Class<UsernamePasswordAuthenticationRequest> getRequestType() {
        return UsernamePasswordAuthenticationRequest.class;
    }

    @Override
    public Uni<SecurityIdentity> authenticate(
            UsernamePasswordAuthenticationRequest request, AuthenticationRequestContext context) {
        String username = request.getUsername();
        String password = new String(request.getPassword().getPassword());
        RoutingContext http = routingContextOf(request);
        ClientOrigin origin = ClientOrigin.of(http);
        // Posted with the password rather than exchanged for a half-authenticated token. There is
        // then no intermediate state to expire, to leak, or to be replayed — the browser asks
        // again with all three fields when it is told a code is wanted.
        String code = http == null ? null : http.request().getFormAttribute(SECOND_FACTOR_FIELD);

        return log.guard(username, origin)
                .onFailure(TooManyAttemptsException.class)
                .invoke(refused -> record(username, null, origin, SignInOutcome.REFUSED_TOO_MANY))
                .flatMap(ignored -> identities.credentialsOf(username))
                .flatMap(
                        credentials ->
                                context.runBlocking(() -> verify(credentials, password))
                                        .onItem()
                                        .transformToUni(
                                                identity ->
                                                        secondFactor(
                                                                        credentials,
                                                                        identity,
                                                                        code,
                                                                        http)
                                                                .flatMap(
                                                                        accepted ->
                                                                                settle(
                                                                                        username,
                                                                                        credentials,
                                                                                        origin,
                                                                                        accepted,
                                                                                        identity
                                                                                                != null))));
    }

    /**
     * Runs on a worker thread. The identity, or null when the password was not right.
     *
     * <p>Null rather than a thrown refusal, because the attempt has to be written down either way
     * and a returned value is easier to carry through a reactive chain than a thrown one is to
     * catch in the middle of it. The refusal itself is raised in {@link #settle}.
     *
     * <p>The comparison runs even when there is no account, against a hash of something nobody
     * knows. Skipping it would answer "is this a real account?" in the time the response took.
     */
    private SecurityIdentity verify(Credentials credentials, String password) {
        String stored = credentials.storedHash() == null ? decoy : credentials.storedHash();
        boolean matched = hasher.matches(password, stored);
        if (!matched || credentials.storedHash() == null) {
            return null;
        }
        return identities.identity(credentials);
    }

    /**
     * The second question, asked only once the first was answered.
     *
     * <p>Only when the password was right, and that ordering is not politeness. Checking a code
     * against a wrong password would let anybody who knows a username burn through somebody's
     * recovery codes, because accepting one spends it.
     *
     * <p>On a context of its own, because this is the one thing an identity provider has to know
     * that needs a database and cannot be loaded in advance: a recovery code is spent by being
     * accepted, and a write that nobody waited for would let the same code in twice.
     */
    private Uni<SecurityIdentity> secondFactor(
            Credentials credentials, SecurityIdentity identity, String code, RoutingContext http) {
        if (identity == null) {
            return Uni.createFrom().nullItem();
        }
        return OwnContext.call(
                        vertx,
                        () ->
                                secondFactors.accepts(
                                        credentials.userId(), code, java.time.Instant.now()))
                .map(
                        accepted -> {
                            if (Boolean.TRUE.equals(accepted)) {
                                return identity;
                            }
                            // The login page cannot tell a wrong password from a wanted code out of
                            // a bare 401, and it has to: one means try again, the other means show
                            // a field that is not on the form yet. A header rather than a body,
                            // because form authentication writes the response itself.
                            if (http != null && !http.response().ended()) {
                                http.response().putHeader(SECOND_FACTOR_HEADER, "required");
                            }
                            return null;
                        });
    }

    /** Writes the attempt down, then either hands back the identity or refuses. */
    private Uni<SecurityIdentity> settle(
            String username,
            Credentials credentials,
            ClientOrigin origin,
            SecurityIdentity identity,
            boolean passwordWasRight) {
        // Which kind of failure is decided out here rather than inside the check: whether an
        // account exists is known before the hash is compared, and knowing it here costs nothing
        // that the comparison has not already spent.
        SignInOutcome outcome =
                identity != null
                        ? SignInOutcome.SUCCEEDED
                        : credentials.storedHash() == null
                                ? SignInOutcome.NO_SUCH_ACCOUNT
                                : passwordWasRight
                                        ? SignInOutcome.WRONG_SECOND_FACTOR
                                        : SignInOutcome.WRONG_PASSWORD;
        record(username, credentials.userId(), origin, outcome);
        if (identity == null) {
            // One message for both, because "no such user" and "wrong password" are the same
            // fact to anybody entitled to know either.
            return Uni.createFrom()
                    .failure(new AuthenticationFailedException("Wrong username or password"));
        }
        return Uni.createFrom().item(identity);
    }

    /**
     * Writes the attempt down without anybody waiting for it.
     *
     * <p>On a context of its own, because this is being started from the middle of an
     * authentication whose own session is about to end, and a write that joined it would be a write
     * on a closed session under load.
     */
    private void record(String username, Long userId, ClientOrigin origin, SignInOutcome outcome) {
        OwnContext.run(
                vertx,
                () ->
                        outcome == SignInOutcome.SUCCEEDED
                                ? log.succeeded(username, userId, origin, SignInLog.PASSWORD)
                                : log.failed(username, userId, origin, outcome, SignInLog.PASSWORD),
                unwritable ->
                        LOG.debugf(
                                unwritable, "Could not record a sign-in attempt for %s", outcome));
    }

    /**
     * The request being authenticated, taken off the authentication request itself.
     *
     * <p>Not from {@code CurrentVertxRequest}, which is the obvious way and does not work here: the
     * request scope is not active while an identity provider runs, so asking for it throws rather
     * than answering null. Quarkus puts the routing context on the authentication request as an
     * attribute for exactly this — a provider that wants to know where the attempt came from.
     */
    private static RoutingContext routingContextOf(UsernamePasswordAuthenticationRequest request) {
        return HttpSecurityUtils.getRoutingContextAttribute(request);
    }
}
