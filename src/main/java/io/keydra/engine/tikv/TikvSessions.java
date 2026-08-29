package io.keydra.engine.tikv;

import io.keydra.connections.entity.ConnectionProfile;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;
import org.tikv.common.TiConfiguration;
import org.tikv.common.TiSession;
import org.tikv.raw.RawKVClient;

/**
 * One TiKV session per cluster, held for as long as its profiles are used.
 *
 * <p>A session discovers the cluster through its placement driver and keeps a connection to every
 * store — which is why it is held rather than made per request, the same reason every driver of
 * this shape is held.
 *
 * <p>The address on a TiKV profile is the placement driver's, not a store's. That is worth saying
 * where somebody will read it: the thing this connects to is not the thing that holds the data, and
 * a profile pointing at a store's own port reaches something that will not answer.
 */
@ApplicationScoped
public class TikvSessions {

    private static final Logger LOG = Logger.getLogger(TikvSessions.class);

    private final Map<String, TiSession> sessions = new ConcurrentHashMap<>();
    private final Map<Long, String> byProfile = new ConcurrentHashMap<>();

    public RawKVClient clientFor(ConnectionProfile profile) {
        String endpoints = profile.host + ":" + profile.port;
        if (profile.id != null) {
            byProfile.put(profile.id, endpoints);
        }
        TiSession session =
                sessions.computeIfAbsent(
                        endpoints,
                        address -> TiSession.create(TiConfiguration.createRawDefault(address)));
        /*
         * A client per call rather than one held beside the session. It is a thin handle over the
         * session's own connections — the session is the expensive thing — and holding one would
         * mean deciding when to close it while a scan somewhere else was still reading through it.
         */
        return session.createRawClient();
    }

    /** Lets go of a profile's session, which the next use rebuilds. */
    public void forget(Long profileId) {
        String endpoints = byProfile.remove(profileId);
        if (endpoints == null || byProfile.containsValue(endpoints)) {
            // Sessions are keyed on the cluster, so two profiles on one cluster share a session
            // and closing it because one of them was edited would take it from the other.
            return;
        }
        close(sessions.remove(endpoints));
    }

    /**
     * Touches the shaded collections once, on one thread, before anything can race for them.
     *
     * <p>Not superstition. The TiKV client shades Guava under {@code org.tikv.shade}, and {@code
     * ImmutableList}'s own static initializer builds an iterator over the empty list it is in the
     * middle of creating. Java's rule for a class whose initialization is already under way <em>on
     * the same thread</em> is to proceed rather than deadlock — so when another class in that
     * shaded set re-enters it mid-initialization, the iterator is handed a field that is still null
     * and startup dies with {@code Cannot invoke ImmutableList.size() because "list" is null}.
     *
     * <p>Which class touches it first depends on the order beans happen to be created in, which is
     * why it appeared perhaps one boot in ten and never in the same place twice.
     *
     * <p><b>This is not the whole fix, and phase 55 was wrong to say it was.</b> {@link
     * StartupEvent} is runtime initialisation, and the failure that kept happening is in a static
     * initialisation recorder inside {@code ApplicationImpl}'s own {@code <clinit>} — strictly
     * earlier, so this could never reach it. {@code common.config.ShadedCollectionsFirst} is the
     * one that runs early enough. This stays because it is free and covers the bean-creation path
     * it was written for.
     *
     * <p>Failing quietly on purpose. This is a precaution against somebody else's initializer, and
     * an installation with no TiKV target should not fail to start because a precaution it does not
     * need could not be taken.
     */
    void warmShadedCollections(@Observes StartupEvent starting) {
        try {
            Class.forName(
                    "org.tikv.shade.com.google.common.collect.ImmutableList",
                    true,
                    TikvSessions.class.getClassLoader());
        } catch (Throwable notThere) {
            LOG.debugf("Could not pre-load the TiKV client's collections: %s", notThere.toString());
        }
    }

    void closeAll(@Observes ShutdownEvent shutdown) {
        sessions.values().forEach(TikvSessions::close);
        sessions.clear();
        LOG.debug("Closed every TiKV session");
    }

    private static void close(TiSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (Exception closing) {
            LOG.debugf(closing, "A TiKV session did not close cleanly");
        }
    }
}
