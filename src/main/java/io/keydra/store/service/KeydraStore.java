package io.keydra.store.service;

import io.smallrye.mutiny.Uni;
import java.time.Duration;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Somewhere Keydra keeps what it has already worked out, and how instances talk to each other.
 *
 * <p>Two jobs rather than one, because they are the same job seen twice: a cache shared by several
 * instances is only correct if something can tell the others to forget, and the thing that tells
 * them is a channel. Splitting them into two interfaces would mean two connections to the same
 * server and one more thing to configure.
 *
 * <p>Implementations are a CDI alternative of each other. The in-process one is the default and is
 * what a single Keydra wants; the shared one takes over when an address is configured. Nothing
 * above this interface knows which is in use, and no behaviour may depend on knowing — a cache that
 * only works when it is shared is a cache that is wrong on somebody's laptop.
 */
public interface KeydraStore {

    /** Whether this store reaches beyond the process, which is what makes fan-out possible. */
    boolean isShared();

    /**
     * What is held under a key, or empty when nothing is.
     *
     * <p>Values are strings because everything above this serialises its own: a store that took
     * objects would have to know how to write them, and the one thing worse than a stale cache is a
     * cache that disagrees with the API about what a field is called.
     */
    Uni<Optional<String>> get(String key);

    /**
     * Asks the store whether it is there, and fails when it is not.
     *
     * <p>Its own method because every other one here fails soft on purpose: a cache that cannot be
     * reached is a cache miss, and {@link #get} answers empty rather than failing so a request does
     * not die because a cache did. That is right for reading and wrong for asking — a health check
     * built on {@code get} reports a store that has been stopped as reachable, which it did, and
     * which is the confidently wrong answer a status page must never give.
     */
    Uni<Void> ping();

    /** Holds a value for as long as the expiry says, and no longer. */
    Uni<Void> put(String key, String value, Duration expiry);

    /** Drops one key. Silent when there was nothing there. */
    Uni<Void> forget(String key);

    /**
     * Drops every key beneath a prefix.
     *
     * <p>For the invalidations that are about a subject rather than a fact: a grant that changed
     * affects everything cached about that account, and naming each one would mean this layer
     * knowing what the layers above it cache.
     */
    Uni<Void> forgetUnder(String prefix);

    /** Sends a message to every instance subscribed to the channel, including none. */
    Uni<Void> publish(String channel, String message);

    /**
     * Hands every message on a channel to a listener, for as long as the application runs.
     *
     * <p>Subscribed once at startup rather than per caller: a channel here carries a kind of event,
     * not one caller's interest in it, and a subscription per interested bean would open a
     * connection per bean.
     */
    void subscribe(String channel, Consumer<String> listener);
}
