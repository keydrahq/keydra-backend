package io.keydra.authz.service;

import io.keydra.authz.dto.SessionSummary;
import io.keydra.authz.entity.UserSession;
import io.keydra.authz.persistence.SessionRepository;
import io.keydra.store.service.KeydraStore;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import io.vertx.core.http.Cookie;
import io.vertx.core.http.CookieSameSite;
import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Sessions as things: issued, listed, and ended.
 *
 * <p>Keydra's session cookie is a good one — encrypted, signed, `HttpOnly`, `SameSite`, expiring —
 * and until this existed it was also the whole of the session: the server kept no record of having
 * issued it. So there was no list of who was signed in, no way to end one without ending them all,
 * and a cookie somebody took was good until it expired. Changing a password did not stop it,
 * because there was nothing to stop.
 *
 * <p>A second cookie carries the id of a row. The first cookie still does what it did — it is what
 * proves the name has not been tampered with — and this one is what makes the session something
 * that can be looked at and taken away. Two cookies rather than one because the first is written by
 * the framework's own form authentication, and a mechanism that had to be replaced to be extended
 * is a mechanism that gets replaced badly.
 */
@ApplicationScoped
public class Sessions {

    /** The cookie carrying the session id. Named beside the framework's own so both read alike. */
    public static final String COOKIE = "keydra_sid";

    /**
     * The channel every instance hears an ending on.
     *
     * <p>What travels on it is a session id, and what every instance does with one is drop what it
     * had cached and close anything still open on that session. Ending a session has to reach the
     * machine the browser is talking to, and where there is more than one Keydra that is not the
     * machine somebody pressed the button on.
     */
    public static final String ENDED_CHANNEL = "session-ended";

    /** Where a session's "still good" answer is kept between requests. */
    private static final String CACHE_PREFIX = "session:";

    /**
     * How long that answer stands.
     *
     * <p>Short, and the safety net rather than the mechanism: an ending is published the moment it
     * happens and every instance drops what it held. This covers the message nobody heard.
     */
    private static final Duration CACHE_TTL = Duration.ofSeconds(20);

    /** The most rows one request may ask for. */
    private static final int MAX_PAGE = 200;

    /**
     * How stale a session's "last used" may get.
     *
     * <p>Every request presents a session, so writing the timestamp on each one would put a write
     * in front of every call to record something nobody reads to the minute.
     */
    private static final Duration TOUCH_INTERVAL = Duration.ofMinutes(5);

    private final SessionRepository sessions;
    private final SessionTouches touches;
    private final KeydraStore store;
    private final Duration lifetime;
    private final String cookiePath;
    private final String sameSite;
    private final String frameworkCookie;
    private final boolean cookieSecure;

    @Inject
    Sessions(
            SessionRepository sessions,
            SessionTouches touches,
            KeydraStore store,
            @ConfigProperty(name = "quarkus.http.auth.form.timeout") Duration lifetime,
            @ConfigProperty(name = "quarkus.http.auth.form.cookie-path") String cookiePath,
            @ConfigProperty(name = "quarkus.http.auth.form.cookie-same-site") String sameSite,
            @ConfigProperty(name = "quarkus.http.auth.form.cookie-name") String frameworkCookie,
            @ConfigProperty(name = "keydra.security.cookie-secure") boolean cookieSecure) {
        this.sessions = sessions;
        this.touches = touches;
        this.store = store;
        this.lifetime = lifetime;
        this.cookiePath = cookiePath;
        this.sameSite = sameSite;
        this.frameworkCookie = frameworkCookie;
        this.cookieSecure = cookieSecure;
    }

    /**
     * Starts a session for somebody who has just signed in, and puts its id in a cookie.
     *
     * <p>Every setting on the cookie is the one the framework's own session cookie uses, read from
     * the same properties: two cookies that travel together and expire together, or the second one
     * is a way for a session to half-exist.
     */
    @WithTransaction
    public Uni<UserSession> begin(Long userId, RoutingContext context) {
        Instant now = Instant.now();
        UserSession session = new UserSession();
        session.id = UUID.randomUUID().toString();
        session.userId = userId;
        session.issuedAt = now;
        session.lastSeenAt = now;
        session.expiresAt = now.plus(lifetime);
        session.userAgent = describe(context);
        session.network = networkOf(context);

        return sessions.save(session).invoke(saved -> writeCookie(context, saved));
    }

    /** The session a request is presenting, or null when it presents none. */
    public static String presented(RoutingContext context) {
        Cookie cookie = context == null ? null : context.request().getCookie(COOKIE);
        return cookie == null ? null : cookie.getValue();
    }

    /**
     * Whether a session may still be used, noting that it was.
     *
     * <p>The touch is on a slow clock and deliberately not awaited by the caller: a request is not
     * made slower to record that it happened.
     */
    @WithSession
    public Uni<Boolean> isLive(String id) {
        Instant now = Instant.now();
        String key = CACHE_PREFIX + id;

        // Every signed-in request asks this, so it is answered from the store when it can be.
        // Reading the row each time would put a query in front of every call — the same query
        // load phase 21 removed from the identity behind the cookie, reintroduced one layer down.
        return store.get(key)
                .flatMap(
                        held -> {
                            if (held.isPresent()) {
                                return Uni.createFrom().item("live".equals(held.get()));
                            }
                            return fromDatabase(id, now)
                                    .call(
                                            live ->
                                                    store.put(
                                                            key,
                                                            live ? "live" : "ended",
                                                            CACHE_TTL));
                        });
    }

    /** The answer as the row has it, and a note that the session was used. */
    private Uni<Boolean> fromDatabase(String id, Instant now) {
        return sessions.byId(id)
                .map(
                        session -> {
                            if (session == null || !session.isLive(now)) {
                                return false;
                            }
                            if (session.lastSeenAt == null
                                    || session.lastSeenAt.isBefore(now.minus(TOUCH_INTERVAL))) {
                                touch(id, now);
                            }
                            return true;
                        });
    }

    /**
     * Says that a session has ended, everywhere.
     *
     * <p>Two things at once, and both are needed. Dropping the cached answer is what makes the next
     * request on that session go to the row and be refused; publishing is what carries that to the
     * other instances — including the one the browser is actually talking to, which where there is
     * more than one Keydra is not the one that was asked to end it.
     */
    private Uni<Void> announceEnded(String id) {
        return store.forget(CACHE_PREFIX + id).flatMap(ignored -> store.publish(ENDED_CHANNEL, id));
    }

    /** Records that a session is in use, without making anybody wait for it. */
    private void touch(String id, Instant at) {
        // Handed to a bean of its own, which gives it a context and a session of its own. See
        // SessionTouches for what subscribing to it here instead used to do to the request.
        touches.note(id, at);
    }

    /** Ends one session, if it belongs to the account asking. */
    @WithTransaction
    public Uni<Boolean> end(String id, Long userId) {
        return sessions.revoke(id, userId, Instant.now())
                .flatMap(
                        changed ->
                                changed == 0
                                        ? Uni.createFrom().item(false)
                                        : announceEnded(id).replaceWith(true));
    }

    /**
     * Ends every session an account has except the one asking.
     *
     * <p>What "sign out everywhere else" does, and what changing a password should do: somebody who
     * has just acted about their own safety is themselves signed in, and throwing them out would be
     * answering the click with the opposite of what it asked for.
     */
    @WithTransaction
    public Uni<Integer> endOthers(Long userId, String keep) {
        Instant now = Instant.now();
        // The ids are read before they are ended, because after the update there is no way to
        // tell which rows changed — and each one has to be announced by name.
        return sessions.liveFor(userId, now)
                .flatMap(
                        live ->
                                sessions.revokeAllExcept(userId, keep, now)
                                        .call(
                                                ignored ->
                                                        announceAll(
                                                                live.stream()
                                                                        .map(session -> session.id)
                                                                        .filter(
                                                                                id ->
                                                                                        !id.equals(
                                                                                                keep))
                                                                        .toList())));
    }

    /** Announces a set of endings, one message each: a listener acts on one session at a time. */
    private Uni<Void> announceAll(List<String> ids) {
        Uni<Void> announced = Uni.createFrom().voidItem();
        for (String id : ids) {
            announced = announced.flatMap(ignored -> announceEnded(id));
        }
        return announced;
    }

    /** Removes sessions that have lapsed. Called on a schedule; nothing reads them again. */
    @WithTransaction
    public Uni<Integer> sweep() {
        return sessions.sweepExpired(Instant.now());
    }

    // --- Somebody's own sessions, as they see them ---------------------------

    /**
     * One page of the caller's own sessions, described.
     *
     * <p>Here rather than in each transport, because "whose sessions, which one is this, and how
     * many at a time" is a rule and not a way of speaking HTTP. It was written out in the resource;
     * a second surface would have meant writing it out again, and two spellings of one rule is one
     * of them being wrong later.
     *
     * <p>Answers an empty list rather than failing when nobody is signed in. Asking which browsers
     * can act as you when you are nobody has a true answer, and it is "none".
     *
     * @param first how many rows, bounded here rather than trusted — a caller asking for a million
     *     is asking the database for every session an account has ever had open
     */
    @WithSession
    public Uni<List<SessionSummary>> mine(
            Long userId, String currentSessionId, int first, int offset) {
        if (userId == null) {
            return Uni.createFrom().item(List.of());
        }
        return sessions.livePageFor(userId, Instant.now(), currentSessionId, bounded(first), offset)
                .map(
                        found ->
                                found.stream()
                                        .map(
                                                session ->
                                                        new SessionSummary(
                                                                session.id,
                                                                session.id.equals(currentSessionId),
                                                                session.issuedAt,
                                                                session.lastSeenAt,
                                                                session.expiresAt,
                                                                session.userAgent,
                                                                session.network))
                                        .toList());
    }

    /** How many of the caller's browsers there are to page through. */
    @WithSession
    public Uni<Long> mineCount(Long userId) {
        return userId == null
                ? Uni.createFrom().item(0L)
                : sessions.liveCountFor(userId, Instant.now());
    }

    /**
     * As many rows as anybody has any business asking for at once.
     *
     * <p>The same bound the sign-in list uses, for the same reason: a page size is a request from
     * the client, and a request is not a permission.
     */
    private static int bounded(int first) {
        return Math.max(1, Math.min(first, MAX_PAGE));
    }

    /** Ends one of the caller's own, answering whether there was one of theirs to end. */
    public Uni<Boolean> endMine(String id, Long userId) {
        return userId == null ? Uni.createFrom().item(false) : end(id, userId);
    }

    /** Ends every session but the one asking, and answers how many. */
    public Uni<Integer> endMyOthers(Long userId, String currentSessionId) {
        return userId == null ? Uni.createFrom().item(0) : endOthers(userId, currentSessionId);
    }

    // --- The cookie ---------------------------------------------------------

    private void writeCookie(RoutingContext context, UserSession session) {
        if (context == null) {
            return;
        }
        Cookie cookie =
                Cookie.cookie(COOKIE, session.id)
                        .setPath(cookiePath)
                        .setHttpOnly(true)
                        .setSameSite(
                                CookieSameSite.valueOf(sameSite.toUpperCase(java.util.Locale.ROOT)))
                        // Configuration rather than whether this request arrived over TLS.
                        // The normal way to run Keydra puts a proxy in front that terminates TLS,
                        // so the request reaching here is plain HTTP and asking it would have
                        // dropped the flag on exactly the deployments that need it. The framework
                        // cookie reads the same property, so the two halves of one session cannot
                        // disagree about how carefully they are carried.
                        .setSecure(cookieSecure)
                        .setMaxAge(lifetime.toSeconds());
        context.response().addCookie(cookie);
    }

    /** Clears the cookie, for signing out. */
    public void clearCookie(RoutingContext context) {
        if (context == null) {
            return;
        }
        context.response().addCookie(Cookie.cookie(COOKIE, "").setPath(cookiePath).setMaxAge(0));
    }

    /**
     * Clears both cookies, for a browser holding a session that has ended.
     *
     * <p>Refusing such a request is only half of it. The framework's cookie is still valid — it
     * says who signed in, and nothing has told it otherwise — so a browser that keeps it presents
     * it again on the next request, is refused again, and never reaches a state anything can
     * recover from: the sign-in page does not come back, because as far as the browser is concerned
     * it is still signed in. Taking both cookies away leaves it where clearing them by hand leaves
     * it, which is signed out.
     */
    public void clearBothCookies(RoutingContext context) {
        if (context == null) {
            return;
        }
        clearCookie(context);
        context.response()
                .addCookie(Cookie.cookie(frameworkCookie, "").setPath(cookiePath).setMaxAge(0));
    }

    // --- What is remembered about where a session came from -----------------

    /** The browser as it described itself, truncated. It is not evidence, only a label. */
    private static String describe(RoutingContext context) {
        if (context == null) {
            return null;
        }
        String agent = context.request().getHeader("User-Agent");
        if (agent == null || agent.isBlank()) {
            return null;
        }
        return agent.length() > 400 ? agent.substring(0, 400) : agent;
    }

    /**
     * The network an address belongs to, not the address.
     *
     * <p>Enough for somebody to say "that is not where I work" and not enough to be a record of
     * their movements — which is what a full address in a table nobody prunes becomes.
     */
    static String networkOf(RoutingContext context) {
        if (context == null || context.request().remoteAddress() == null) {
            return null;
        }
        String address = context.request().remoteAddress().host();
        if (address == null || address.isBlank()) {
            return null;
        }
        if (address.contains(":")) {
            String[] groups = address.split(":");
            return groups.length >= 4
                    ? String.join(":", groups[0], groups[1], groups[2], groups[3]) + "::"
                    : address;
        }
        String[] parts = address.split("\\.");
        return parts.length == 4 ? String.join(".", parts[0], parts[1], parts[2], "0") : address;
    }
}
