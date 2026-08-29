package io.keydra.engine;

import io.keydra.connections.entity.ConnectionProfile;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Any;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.EnumMap;
import java.util.Map;

/**
 * Resolves a profile to the engine that can serve it.
 *
 * <p>Engines are discovered as CDI beans, so a new backing store is added by dropping in an
 * implementation — nothing here or above needs editing.
 */
@ApplicationScoped
public class EngineSelector {

    private final Map<EngineType, KeyValueEngine> engines = new EnumMap<>(EngineType.class);

    @Inject
    EngineSelector(@Any Instance<KeyValueEngine> discovered) {
        discovered.forEach(engine -> engines.put(engine.type(), engine));
    }

    public KeyValueEngine forProfile(ConnectionProfile profile) {
        return forType(profile.engine);
    }

    public KeyValueEngine forType(EngineType type) {
        KeyValueEngine engine = engines.get(type);
        if (engine == null) {
            throw new IllegalStateException("No engine registered for " + type);
        }
        return engine;
    }

    /** Every engine, so a profile-independent action can be applied to all of them. */
    public Iterable<KeyValueEngine> all() {
        return engines.values();
    }
}
