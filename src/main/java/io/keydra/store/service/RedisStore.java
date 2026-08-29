package io.keydra.store.service;

import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import io.vertx.core.net.NetClientOptions;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.RedisOptions;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.jboss.logging.Logger;

/**
 * The store several Keydras share.
 *
 * <p>Its own server, never one of the targets. What is cached here is Keydra's own working state,
 * and a cache living in a server somebody is browsing is a cache somebody empties with a bulk
 * delete — so this is configured separately, and the dev pod runs a fourth container for it.
 *
 * <p>Nothing here may fail its caller. A store that is unreachable means work that has to be done
 * again, not work that cannot be done: a failed read answers "nothing cached", a failed write is
 * dropped, and the caller goes to the database exactly as it did before this phase existed. The one
 * thing worse than no cache is a cache whose absence takes the application down with it.
 */
public class RedisStore implements KeydraStore {

    private static final Logger LOG = Logger.getLogger(RedisStore.class);

    /** How many keys a SCAN asks for at a time while clearing a prefix. */
    private static final String SCAN_BATCH = "500";

    /** How long to wait before dialling the subscriber connection again. */
    private static final Duration RESUBSCRIBE = Duration.ofSeconds(5);

    private final Vertx vertx;
    private final Redis client;
    private final String prefix;

    private final Map<String, List<Consumer<String>>> listeners = new ConcurrentHashMap<>();

    /** The subscribing connection, which cannot be the one that publishes. */
    private volatile RedisConnection subscriber;

    private volatile boolean closing;

    public RedisStore(Vertx vertx, String url, String prefix, int poolSize) {
        this.vertx = vertx;
        this.prefix = prefix;
        this.client =
                Redis.createClient(
                        vertx,
                        new RedisOptions()
                                .setConnectionString(url)
                                .setMaxPoolSize(poolSize)
                                .setNetClientOptions(netOptions(url)));
    }

    /**
     * How the connection to the store is made, and verified when it is encrypted.
     *
     * <p>Without this a {@code rediss://} store could not be reached at all: Vert.x will not open a
     * TLS connection without being told how to check the hostname, and refuses with "Missing
     * hostname verification algorithm" — the same refusal that stopped every TLS target working.
     * Which meant the one connection carrying this instance's cache of who may do what could only
     * be made in the clear.
     *
     * <p>That matters more here than it does for a target. What travels over this connection is the
     * identity behind a session cookie and the permissions it carries; anything able to read or
     * write it can learn who is signed in, and forge what they are allowed to do until the entry
     * expires. A shared store reached across a network should be {@code rediss://} with a password,
     * and until now it could not be.
     *
     * <p>Verified against the name in the address, always: the store is dialled directly and there
     * is no tunnel case where the certificate could honestly name something else.
     */
    private static NetClientOptions netOptions(String url) {
        NetClientOptions options = new NetClientOptions();
        if (url != null && url.startsWith("rediss://")) {
            options.setSsl(true).setTrustAll(false).setHostnameVerificationAlgorithm("HTTPS");
        }
        return options;
    }

    @Override
    public boolean isShared() {
        return true;
    }

    @Override
    public Uni<Void> ping() {
        // The one call here that is allowed to fail. Everything else recovers, because a cache
        // that cannot be reached is a cache miss; this exists so somebody can be told the
        // difference between an empty cache and an absent one.
        return send(Request.cmd(Command.PING)).replaceWithVoid();
    }

    @Override
    public Uni<Optional<String>> get(String key) {
        return send(Request.cmd(Command.GET).arg(prefixed(key)))
                .map(response -> Optional.ofNullable(response).map(Response::toString))
                .onFailure()
                .recoverWithItem(
                        failure -> {
                            LOG.debugf(failure, "Could not read %s from the store", key);
                            return Optional.empty();
                        });
    }

    @Override
    public Uni<Void> put(String key, String value, Duration expiry) {
        // PX rather than EX: expiries here are seconds or fractions of one, and an entry
        // rounded up to a whole second is a revocation that lingers for one.
        return send(Request.cmd(Command.SET)
                        .arg(prefixed(key))
                        .arg(value)
                        .arg("PX")
                        .arg(Math.max(1, expiry.toMillis())))
                .replaceWithVoid()
                .onFailure()
                .recoverWithItem(
                        failure -> {
                            LOG.debugf(failure, "Could not write %s to the store", key);
                            return null;
                        });
    }

    @Override
    public Uni<Void> forget(String key) {
        return send(Request.cmd(Command.DEL).arg(prefixed(key)))
                .replaceWithVoid()
                .onFailure()
                .recoverWithItem(
                        failure -> {
                            LOG.debugf(failure, "Could not drop %s from the store", key);
                            return null;
                        });
    }

    /**
     * Clears a prefix by walking it.
     *
     * <p>SCAN rather than KEYS, which is the rule everywhere in this codebase and is not relaxed
     * because the server happens to be Keydra's own: this one holds every instance's cached state,
     * so a command that blocks it blocks all of them at once.
     */
    @Override
    public Uni<Void> forgetUnder(String keyPrefix) {
        return scanAndDelete("0", prefixed(keyPrefix) + "*")
                .onFailure()
                .recoverWithItem(
                        failure -> {
                            LOG.debugf(failure, "Could not clear %s from the store", keyPrefix);
                            return null;
                        });
    }

    private Uni<Void> scanAndDelete(String cursor, String match) {
        return send(Request.cmd(Command.SCAN)
                        .arg(cursor)
                        .arg("MATCH")
                        .arg(match)
                        .arg("COUNT")
                        .arg(SCAN_BATCH))
                .flatMap(
                        response -> {
                            String next = response.get(0).toString();
                            List<String> found = new ArrayList<>();
                            response.get(1).forEach(key -> found.add(key.toString()));
                            Uni<Void> deleted =
                                    found.isEmpty()
                                            ? Uni.createFrom().voidItem()
                                            : deleteAll(found);
                            return "0".equals(next)
                                    ? deleted
                                    : deleted.flatMap(ignored -> scanAndDelete(next, match));
                        });
    }

    private Uni<Void> deleteAll(List<String> keys) {
        Request request = Request.cmd(Command.DEL);
        keys.forEach(request::arg);
        return send(request).replaceWithVoid();
    }

    @Override
    public Uni<Void> publish(String channel, String message) {
        return send(Request.cmd(Command.PUBLISH).arg(prefixed(channel)).arg(message))
                .replaceWithVoid()
                .onFailure()
                .recoverWithItem(
                        failure -> {
                            LOG.debugf(failure, "Could not publish on %s", channel);
                            return null;
                        });
    }

    @Override
    public void subscribe(String channel, Consumer<String> listener) {
        boolean first = !listeners.containsKey(channel);
        listeners.computeIfAbsent(channel, ignored -> new CopyOnWriteArrayList<>()).add(listener);
        if (first) {
            connectSubscriber(channel);
        }
    }

    /**
     * Opens the connection that listens, and keeps it open.
     *
     * <p>A separate connection, because Redis will take nothing but subscribe commands on one that
     * is subscribed. Re-dialled on a timer, because the store going away must not be the end of
     * fan-out for the lifetime of the process — the instances have to find each other again when it
     * comes back.
     */
    private void connectSubscriber(String channel) {
        if (closing) {
            return;
        }
        client.connect()
                .onSuccess(
                        connection -> {
                            subscriber = connection;
                            connection.handler(this::onMessage);
                            // A dropped connection is not an error anybody can act on; it is a
                            // reason to dial again.
                            connection.endHandler(ignored -> retry(channel, null));
                            connection.exceptionHandler(failure -> retry(channel, failure));
                            connection
                                    .send(Request.cmd(Command.SUBSCRIBE).arg(prefixed(channel)))
                                    .onSuccess(
                                            ignored ->
                                                    LOG.debugf(
                                                            "Listening for %s from the other"
                                                                    + " instances",
                                                            channel))
                                    .onFailure(failure -> retry(channel, failure));
                        })
                .onFailure(failure -> retry(channel, failure));
    }

    private void retry(String channel, Throwable failure) {
        if (closing) {
            return;
        }
        LOG.debugf(failure, "Not subscribed to %s; trying again shortly", channel);
        vertx.setTimer(RESUBSCRIBE.toMillis(), ignored -> connectSubscriber(channel));
    }

    /**
     * One message off the wire.
     *
     * <p>Redis delivers a subscription message as three parts: the word "message", the channel and
     * the payload. Anything else on this connection is the acknowledgement of the subscribe itself,
     * which is not news.
     */
    private void onMessage(Response response) {
        if (response == null || response.size() != 3) {
            return;
        }
        if (!"message".equals(response.get(0).toString())) {
            return;
        }
        String channel = unprefixed(response.get(1).toString());
        String payload = response.get(2).toString();
        listeners
                .getOrDefault(channel, List.of())
                .forEach(
                        listener -> {
                            try {
                                listener.accept(payload);
                            } catch (RuntimeException misbehaving) {
                                // One listener throwing must not take the subscription with it.
                                LOG.debug("A store listener failed", misbehaving);
                            }
                        });
    }

    public void close() {
        closing = true;
        RedisConnection open = subscriber;
        if (open != null) {
            open.close();
        }
        client.close();
    }

    private Uni<Response> send(Request request) {
        return Uni.createFrom().completionStage(() -> client.send(request).toCompletionStage());
    }

    private String prefixed(String key) {
        return prefix + key;
    }

    private String unprefixed(String key) {
        return key.startsWith(prefix) ? key.substring(prefix.length()) : key;
    }
}
