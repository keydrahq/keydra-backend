package io.keydra.store.config;

import io.keydra.store.service.KeydraStore;
import io.keydra.store.service.MemoryStore;
import io.keydra.store.service.RedisStore;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Disposes;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Decides which store this Keydra has.
 *
 * <p>An address or nothing. Nothing is the default and is a complete answer for the deployment most
 * people run — one Keydra, one memory, no dependency to install. An address makes the same
 * behaviour shared, which is what more than one Keydra needs before its caches are correct and its
 * notifications reach everybody.
 *
 * <p>The choice is made once, at startup, and never re-read. A store that could change underneath
 * the application would mean two caches disagreeing about who holds what, and the failure would
 * look like a permission that came back after being revoked.
 */
@ApplicationScoped
public class StoreProvider {

    private static final Logger LOG = Logger.getLogger(StoreProvider.class);

    @Produces
    @Singleton
    KeydraStore store(
            Vertx vertx,
            MemoryStore memory,
            @ConfigProperty(name = "keydra.store.url") Optional<String> url,
            @ConfigProperty(name = "keydra.store.key-prefix", defaultValue = "keydra:")
                    String prefix,
            @ConfigProperty(name = "keydra.store.pool-size", defaultValue = "8") int poolSize) {
        if (url.isEmpty() || url.get().isBlank()) {
            LOG.info("Caching in this process; set keydra.store.url to share it between instances");
            return memory;
        }
        // The address can carry a password, so what is logged is the fact rather than the
        // string — the same rule every other credential in this application follows.
        LOG.infof("Sharing cache and notifications through a store, under keys named %s*", prefix);
        return new RedisStore(vertx, url.get(), prefix, poolSize);
    }

    void close(@Disposes KeydraStore store) {
        if (store instanceof RedisStore shared) {
            shared.close();
        }
    }
}
