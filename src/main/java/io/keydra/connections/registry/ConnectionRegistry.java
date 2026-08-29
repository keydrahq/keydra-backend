package io.keydra.connections.registry;

import io.keydra.common.workload.Workload;
import io.keydra.connections.dto.ConnectionState;
import io.keydra.connections.dto.ConnectionStatus;
import io.keydra.connections.dto.ServerInfo;
import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.engine.EngineSelector;
import io.keydra.events.dto.NotificationCategory;
import io.keydra.events.service.NotificationHub;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which targets are live and what each one last reported.
 *
 * <p>Engine-neutral: the actual talking is delegated to the {@link io.keydra.engine.KeyValueEngine}
 * the profile names, so this class only decides which profiles are being watched, caches their
 * status, and announces changes.
 */
@ApplicationScoped
public class ConnectionRegistry implements Workload {

    private final EngineSelector engines;
    private final NotificationHub hub;

    private final Map<Long, ConnectionStatus> statuses = new ConcurrentHashMap<>();

    /**
     * Targets being watched, kept so the health sweep can re-probe them.
     *
     * <p>The sweep runs on a timer thread where database access is not available, so the details
     * needed to redial are remembered here rather than reloaded. Entries are plain data; nothing
     * reads them back through the persistence context.
     */
    private final Map<Long, ConnectionProfile> watched = new ConcurrentHashMap<>();

    /**
     * The last state each target actually settled in — up or down, never "asking".
     *
     * <p>Kept apart from {@link #statuses} because a listener needs to know what a change is a
     * change *from*, and the entry in that map has already been overwritten by the time anything is
     * broadcast. "Asking" is skipped on purpose: a re-check passes through it every time, so a
     * target that has been up all day would otherwise look as though it had just come back.
     *
     * <p>Absent means never contacted, which is the case this exists for. Learning for the first
     * time that a target is up is not the same event as a target coming back, and only the second
     * one is worth interrupting somebody about.
     */
    private final Map<Long, ConnectionState> settled = new ConcurrentHashMap<>();

    @Inject
    ConnectionRegistry(EngineSelector engines, NotificationHub hub) {
        this.engines = engines;
        this.hub = hub;
    }

    /** Last known status; {@link ConnectionStatus#unknown()} for a profile never contacted. */
    public ConnectionStatus status(Long profileId) {
        return statuses.getOrDefault(profileId, ConnectionStatus.unknown());
    }

    /**
     * How many watched targets are in one state.
     *
     * <p>Asked at scrape time by the gauge that reports it, which is why it counts rather than
     * caching a number somebody would have to keep in step.
     */
    public long countInState(ConnectionState state) {
        return statuses.values().stream().filter(status -> status.state() == state).count();
    }

    /** Ids being watched — the set the health monitor sweeps. */
    public Set<Long> registeredIds() {
        return Set.copyOf(statuses.keySet());
    }

    /**
     * Probes a profile without recording anything.
     *
     * <p>Used by "test connection", which must stay side-effect free so it also serves profiles
     * that have not been saved yet.
     */
    public Uni<ConnectionStatus> test(ConnectionProfile profile) {
        return probe(profile, lastKnownServer(profile.id));
    }

    /** Probes a profile and records the result, broadcasting when the status changes. */
    public Uni<ConnectionStatus> refresh(ConnectionProfile profile) {
        Long id = profile.id;
        // Marked before the probe starts, and synchronously, so a response built straight after
        // this call already reads "connecting" instead of "unknown".
        if (id != null) {
            watched.put(id, profile);
        }
        markConnecting(id);
        return probe(profile, lastKnownServer(id)).invoke(status -> recordStatus(id, status));
    }

    /**
     * Re-probes a target already being watched.
     *
     * <p>Used by the periodic sweep, which knows only ids.
     */
    public Uni<ConnectionStatus> refreshRegistered(Long profileId) {
        ConnectionProfile profile = watched.get(profileId);
        if (profile == null) {
            return Uni.createFrom().item(status(profileId));
        }
        return probe(profile, lastKnownServer(profileId))
                .invoke(status -> recordStatus(profileId, status));
    }

    /** Forgets a profile entirely; called when it is deleted. */
    public void close(Long profileId) {
        invalidate(profileId);
        statuses.remove(profileId);
        watched.remove(profileId);
        settled.remove(profileId);
    }

    /** Drops anything cached for a profile so the next probe reflects edited settings. */
    public void invalidate(Long profileId) {
        engines.all().forEach(engine -> engine.release(profileId));
    }

    private Uni<ConnectionStatus> probe(ConnectionProfile profile, ServerInfo lastKnown) {
        try {
            return engines.forProfile(profile)
                    .describe(profile)
                    .map(ConnectionStatus::up)
                    .onFailure()
                    .recoverWithItem(failure -> toFailure(failure, lastKnown));
        } catch (RuntimeException e) {
            // Selecting or opening an engine can fail outright on a malformed target. Without
            // this the profile would sit at "connecting" for ever, since no probe ever answers.
            return Uni.createFrom().item(toFailure(e, lastKnown));
        }
    }

    private static ConnectionStatus toFailure(Throwable failure, ServerInfo lastKnown) {
        String message = failure.getMessage();
        return ConnectionStatus.down(
                message == null || message.isBlank() ? failure.getClass().getSimpleName() : message,
                lastKnown);
    }

    /**
     * Server details from the last successful probe, so a re-check does not blank the row.
     *
     * <p>Tolerates a null id because {@link #test} accepts profiles that have never been saved, and
     * the status map cannot be keyed by null.
     */
    private ServerInfo lastKnownServer(Long profileId) {
        if (profileId == null) {
            return null;
        }
        ConnectionStatus current = statuses.get(profileId);
        return current == null ? null : current.server();
    }

    /**
     * Flags a profile as being checked right now.
     *
     * <p>Without this a row sits at "unknown" between a page load and the probe answering, which
     * reads as "we have no idea" when the truth is "asking".
     */
    private void markConnecting(Long profileId) {
        if (profileId == null) {
            return;
        }
        recordStatus(profileId, ConnectionStatus.connecting(lastKnownServer(profileId)));
    }

    /**
     * Only real changes are broadcast; a repeated identical check stays silent.
     *
     * <p>The event carries the profile's name as well as its id. A listener that has never loaded
     * the connection list — a console or a key browser opened straight from a link — has no way to
     * turn an id into something worth showing a person, and "target #3 went down" is not a
     * notification anyone can act on.
     *
     * <p>It carries what the target was before as well, and that is the part that decides whether
     * anybody is interrupted. Every listener still hears every change, because a badge that only
     * updated on interesting changes would be wrong the rest of the time; but "it is up" and "it is
     * up again" are different sentences, and only the second one is news. What it was before is the
     * last settled state, so a re-check passing through "asking" does not read as a recovery.
     */
    private void recordStatus(Long profileId, ConnectionStatus status) {
        ConnectionStatus previous = statuses.put(profileId, status);
        ConnectionState previouslySettled =
                status.state() == ConnectionState.CONNECTING
                        ? settled.get(profileId)
                        : settled.put(profileId, status.state());
        if (status.differsFrom(previous)) {
            ConnectionProfile profile = watched.get(profileId);
            Map<String, Object> payload = new java.util.HashMap<>();
            payload.put("id", profileId);
            payload.put("name", profile == null || profile.name == null ? "" : profile.name);
            payload.put("status", status);
            // Null when this is the first thing ever heard about the target. Map.of would
            // refuse it, which is why this one is built rather than declared.
            payload.put("previousState", previouslySettled);
            hub.broadcast(NotificationCategory.CONNECTION_STATUS_CHANGED, profileId, payload);
        }
    }

    /**
     * Which targets this instance holds clients for.
     *
     * <p>Ids rather than a count. The count is what a count of anything is; the question phase 39
     * left open was <em>which</em>, because a target watched by three instances is three pools
     * against one server and that is worth knowing before it is worth finding out.
     */
    @Override
    public Snapshot snapshot() {
        return Snapshot.ofTargets(registeredIds());
    }
}
