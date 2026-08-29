package io.keydra.engine;

import io.keydra.connections.dto.ServerInfo;
import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.keys.dto.KeyEntry;
import io.keydra.values.dto.ValueMutation;
import io.keydra.values.dto.ValuePage;
import io.keydra.values.dto.ValueQuery;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import java.util.List;

/**
 * Everything Keydra needs from a key-value store.
 *
 * <p>The seam that keeps a second backing store from being a rewrite: no protocol type crosses this
 * interface, so services above it deal only in {@link KeyEntry} and {@link ServerInfo}. Adding a
 * store means adding an implementation and an {@link EngineType}, not touching the layers above.
 *
 * <p>Implementations own their own connections and are responsible for closing them; the connection
 * registry only decides which profiles are live and caches their status.
 */
public interface KeyValueEngine {

    /** Which profiles this implementation serves. */
    EngineType type();

    /**
     * Opens a connection and reports what answered.
     *
     * <p>Used both for "test connection" and for periodic health checks, so it must be cheap and
     * must not disturb the store.
     */
    Uni<ServerInfo> describe(ConnectionProfile profile);

    /**
     * Streams matching keys with their type and time to live.
     *
     * <p>Must iterate incrementally: a store holding millions of keys cannot be listed in one
     * response, and must never be blocked while it is browsed.
     */
    Multi<KeyEntry> scanKeys(ConnectionProfile profile, KeyQuery query);

    /**
     * Walks key names only, without describing what each key holds.
     *
     * <p>Separate from {@link #scanKeys} because describing a key costs a command per key: building
     * a namespace tree from ten thousand names was issuing twenty thousand TYPE and TTL calls whose
     * answers were thrown away. A caller that needs only names should not pay for the rest.
     */
    Multi<String> scanKeyNames(ConnectionProfile profile, KeyQuery query);

    /**
     * @return how many keys were actually removed
     */
    Uni<Long> deleteKeys(ConnectionProfile profile, List<String> keys);

    /**
     * @param replace overwrite the destination if it exists
     * @return 1 when the key was renamed, 0 when it was not
     */
    Uni<Long> renameKey(ConnectionProfile profile, String from, String to, boolean replace);

    /**
     * Copies a key to a new name, value and all.
     *
     * <p>Done by the store rather than by reading the value out and writing it back: a large hash
     * would otherwise cross the wire twice, and a value changing in between would be copied
     * half-old and half-new.
     *
     * @param replace overwrite the destination if it exists
     * @return 1 when the key was copied, 0 when the destination existed and was kept
     */
    Uni<Long> copyKey(ConnectionProfile profile, String from, String to, boolean replace);

    /**
     * @param ttlSeconds seconds until expiry, or null to remove the expiry
     * @return 1 when the key's expiry changed, 0 otherwise
     */
    Uni<Long> setExpiry(ConnectionProfile profile, String key, Long ttlSeconds);

    /**
     * Reads one slice of a key's value.
     *
     * <p>Segmented on purpose: a hash or list can hold more than a response should carry, so the
     * query names a cursor and a count and the page says where to resume.
     *
     * <p>Values come back as raw bytes inside {@link io.keydra.values.dto.EncodedValue} only after
     * the decoder chain has run — an engine returns what it read and does not interpret it.
     */
    Uni<ValuePage> readValue(ConnectionProfile profile, ValueQuery query, String encoding);

    /**
     * Applies one change to a value.
     *
     * @return how many elements the store reported as changed
     */
    Uni<Long> mutateValue(ConnectionProfile profile, ValueMutation mutation);

    /** Releases anything held for a profile; called when it is deleted or edited. */
    void release(Long profileId);

    /**
     * This store's command language, when it has one.
     *
     * <p>Empty for a store reached only through typed operations. The console asks before offering
     * itself, so a target without a command line says so rather than failing on the first line
     * typed into it.
     */
    default java.util.Optional<CommandConsole> console() {
        return java.util.Optional.empty();
    }

    /**
     * Changing how this store is configured, when it can be changed while it runs.
     *
     * <p>Empty for a store configured only at startup. The endpoints ask before offering
     * themselves, so a target whose settings are fixed says so rather than accepting a change that
     * will not take.
     */
    default java.util.Optional<ServerAdmin> admin() {
        return java.util.Optional.empty();
    }

    /**
     * This store's live command stream, when it has one.
     *
     * <p>Empty for a store that will not show its own traffic. The endpoint asks before offering
     * itself, so a target that cannot be watched says so rather than opening a socket that will
     * never carry anything.
     */
    default java.util.Optional<CommandStream> commandStream() {
        return java.util.Optional.empty();
    }

    /**
     * This store's publish/subscribe facility, when it has one.
     *
     * <p>Empty for a store that only holds values. The pub/sub endpoints ask before offering
     * themselves, so a target that cannot carry messages says so up front.
     */
    default java.util.Optional<MessageBus> messaging() {
        return java.util.Optional.empty();
    }

    /**
     * This store announcing its own mutations, when it does.
     *
     * <p>Empty for a store that changes silently. The key browser asks before offering to watch, so
     * a target that will never say anything says that instead of opening a watch nothing arrives
     * on.
     */
    default java.util.Optional<KeyspaceEvents> keyspaceEvents() {
        return java.util.Optional.empty();
    }

    /**
     * What this store can report about itself, when it reports anything.
     *
     * <p>Empty for a store that keeps no statistics. The monitoring endpoints ask first, so a
     * target without them says so rather than returning an empty dashboard that looks broken.
     */
    default java.util.Optional<ServerMetrics> metrics() {
        return java.util.Optional.empty();
    }

    /**
     * What this target supports, asked of the target itself.
     *
     * <p>Defaults to assuming everything a server might have and reporting exactly what this engine
     * offers. The two halves answer different questions and it is worth keeping them apart: whether
     * a *server* supports streams is a thing to find out by asking it, and an engine that cannot
     * ask should let the operation fail where it is used rather than hide a feature the target may
     * well have. Whether a *store* has a command language at all is not a question about the server
     * — the engine either implements one or it does not, and it can say so without a round trip.
     *
     * <p>Which is what makes a second engine cheap. An implementation that speaks something other
     * than RESP overrides nothing here: it declines the capabilities it has no answer for by
     * returning empty from {@link #console()} and its neighbours, and this reports that. The
     * alternative — a second list, kept by hand, saying the same thing — is two places to change
     * and one of them to forget.
     */
    default io.smallrye.mutiny.Uni<Capabilities> capabilities(ConnectionProfile profile) {
        return io.smallrye.mutiny.Uni.createFrom().item(declared(Capabilities.assumed()));
    }

    /**
     * A capability set with what this engine actually offers folded into it.
     *
     * <p>Additive for the ones an engine implements and subtractive for the ones it does not, so an
     * engine that probes its server for the rest can hand its answer through here and be right
     * about both halves.
     */
    default Capabilities declared(Capabilities detected) {
        java.util.Set<String> features = new java.util.LinkedHashSet<>(detected.features());
        record Offered(String feature, boolean present) {}
        for (Offered offered :
                java.util.List.of(
                        new Offered(Capabilities.Feature.CONSOLE, console().isPresent()),
                        new Offered(
                                Capabilities.Feature.COMMAND_STREAM, commandStream().isPresent()),
                        new Offered(
                                Capabilities.Feature.ACCESS_CONTROL, accessControl().isPresent()),
                        new Offered(Capabilities.Feature.TRANSFER, transfer().isPresent()),
                        new Offered(Capabilities.Feature.ADMIN, admin().isPresent()),
                        new Offered(Capabilities.Feature.PUB_SUB, messaging().isPresent()),
                        new Offered(
                                Capabilities.Feature.KEYSPACE_EVENTS, keyspaceEvents().isPresent()),
                        new Offered(Capabilities.Feature.METRICS, metrics().isPresent()),
                        new Offered(Capabilities.Feature.TOPOLOGY, topology().isPresent()))) {
            if (offered.present()) {
                features.add(offered.feature());
            } else {
                features.remove(offered.feature());
            }
        }
        return new Capabilities(java.util.Set.copyOf(features), detected.detected());
    }

    /**
     * How this target is arranged, when it is arranged at all.
     *
     * <p>Empty for a store with no notion of nodes. The topology view asks first, so a standalone
     * target says there is nothing to draw rather than drawing an empty diagram.
     */
    /**
     * Taking keys out of this store and putting them back, when it can.
     *
     * <p>Empty for a store that cannot hand a value back as bytes. Import and export are then
     * simply not offered for that store, rather than offered and failing.
     */
    default java.util.Optional<KeyTransfer> transfer() {
        return java.util.Optional.empty();
    }

    default java.util.Optional<Topology> topology() {
        return java.util.Optional.empty();
    }

    /**
     * This store's user list, when it keeps one.
     *
     * <p>Empty for a store with no notion of users. The ACL page asks first, so a target without
     * one says so rather than showing an empty user list that looks like a misconfiguration.
     */
    default java.util.Optional<AccessControl> accessControl() {
        return java.util.Optional.empty();
    }
}
