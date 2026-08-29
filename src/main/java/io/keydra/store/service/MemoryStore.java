package io.keydra.store.service;

import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.enterprise.inject.Typed;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * The store one Keydra needs, which is a map and a list of listeners.
 *
 * <p>The default, and deliberately complete rather than a stub: a single instance gets the whole
 * benefit of the caching this phase adds without installing anything, and the tests that matter run
 * against this one as well as against the shared one. What it cannot do is reach another instance —
 * {@link #isShared()} says so, and the hub uses that to decide whether a broadcast has anywhere
 * else to go.
 *
 * <p>Publishing still calls the local listeners. It costs nothing and it means the subscribe path
 * is exercised in every deployment rather than only in the ones with a Redis, which is where a
 * fan-out bug would otherwise wait.
 */
@ApplicationScoped
@Typed(MemoryStore.class)
public class MemoryStore implements KeydraStore {

    /** A value and the moment it stops being one. */
    private record Held(String value, Instant until) {}

    /**
     * How often expired entries are swept out.
     *
     * <p>Reads check the expiry themselves, so this is not about correctness — it is about a key
     * nobody reads again never being freed. A cache in a long-running process is otherwise a slow
     * leak of everything anybody ever looked at once.
     */
    private static final Duration SWEEP = Duration.ofMinutes(1);

    private final Map<String, Held> held = new ConcurrentHashMap<>();
    private final Map<String, List<Consumer<String>>> listeners = new ConcurrentHashMap<>();
    private final Vertx vertx;
    private long sweepTimer = -1;

    @Inject
    MemoryStore(Instance<Vertx> vertx) {
        // Optional so the store can be built in a plain unit test, where there is no Vert.x
        // to hang a timer on and nothing runs long enough to need one.
        this.vertx = vertx.isResolvable() ? vertx.get() : null;
        if (this.vertx != null) {
            sweepTimer = this.vertx.setPeriodic(SWEEP.toMillis(), id -> sweep());
        }
    }

    @PreDestroy
    void stop() {
        if (vertx != null && sweepTimer != -1) {
            vertx.cancelTimer(sweepTimer);
            sweepTimer = -1;
        }
    }

    @Override
    public boolean isShared() {
        return false;
    }

    /** There is nothing to reach: this store is the process asking. */
    @Override
    public Uni<Void> ping() {
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Optional<String>> get(String key) {
        Held entry = held.get(key);
        if (entry == null) {
            return Uni.createFrom().item(Optional.empty());
        }
        if (entry.until().isBefore(Instant.now())) {
            held.remove(key, entry);
            return Uni.createFrom().item(Optional.empty());
        }
        return Uni.createFrom().item(Optional.of(entry.value()));
    }

    @Override
    public Uni<Void> put(String key, String value, Duration expiry) {
        held.put(key, new Held(value, Instant.now().plus(expiry)));
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> forget(String key) {
        held.remove(key);
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> forgetUnder(String prefix) {
        held.keySet().removeIf(key -> key.startsWith(prefix));
        return Uni.createFrom().voidItem();
    }

    @Override
    public Uni<Void> publish(String channel, String message) {
        listeners.getOrDefault(channel, List.of()).forEach(listener -> listener.accept(message));
        return Uni.createFrom().voidItem();
    }

    @Override
    public void subscribe(String channel, Consumer<String> listener) {
        listeners.computeIfAbsent(channel, ignored -> new CopyOnWriteArrayList<>()).add(listener);
    }

    private void sweep() {
        Instant now = Instant.now();
        held.entrySet().removeIf(entry -> entry.getValue().until().isBefore(now));
    }
}
