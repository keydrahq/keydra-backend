package io.keydra.engine.aerospike;

import com.aerospike.client.Bin;
import com.aerospike.client.Info;
import com.aerospike.client.Value;
import com.aerospike.client.cluster.Node;
import com.aerospike.client.policy.RecordExistsAction;
import com.aerospike.client.policy.ScanPolicy;
import com.aerospike.client.policy.WritePolicy;
import com.aerospike.client.query.KeyRecord;
import io.keydra.common.vertx.OffLoop;
import io.keydra.connections.dto.ServerInfo;
import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.engine.Capabilities;
import io.keydra.engine.EngineTraffic;
import io.keydra.engine.EngineType;
import io.keydra.engine.KeyQuery;
import io.keydra.engine.KeyValueEngine;
import io.keydra.engine.OperationUnsupportedException;
import io.keydra.keys.dto.KeyEntry;
import io.keydra.keys.exception.KeyNotFoundException;
import io.keydra.values.dto.EncodedValue;
import io.keydra.values.dto.ValueMutation;
import io.keydra.values.dto.ValuePage;
import io.keydra.values.dto.ValueQuery;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import mutiny.zero.flow.adapters.AdaptersToFlow;

/**
 * Aerospike, which is the first store here that does not speak RESP.
 *
 * <p>Worth being plain about what this can and cannot offer, because the difference is not a matter
 * of effort. A record is addressed by a namespace, a set and a user key, and the user key is stored
 * only when whoever wrote the record asked for that — the default is not to. So for most existing
 * data there is no name to show, only a digest, and everything that works by name works only for
 * the records that have one. {@link AerospikeKeys} is where that is written down in full.
 *
 * <p>What it does offer: browsing a namespace by set, reading and writing a record's bins, its
 * expiry, and deleting it. What it declines, by returning empty from the capability rather than by
 * failing at the first call: a command language, watching commands, its own user list, pub/sub,
 * handing values over as bytes, and a shape to draw. Each of those is a thing Aerospike does not
 * have, and saying so is what stops the interface offering a tool that cannot work.
 *
 * <p>A record's bins are a set of named values, which is what this application already calls a hash
 * — so that is what a record is reported as, and the value editor for a hash is the editor for a
 * record. That is a mapping rather than a coincidence: both are "a key holding named fields".
 */
@ApplicationScoped
public class AerospikeEngine implements KeyValueEngine {

    /** The only type this store has: a record is named bins, which is a hash here. */
    private static final String ONLY_TYPE = "hash";

    /** How many records a scan holds in flight, matching what a RESP scan asks for at a time. */
    private static final int SCAN_BUFFER = 500;

    private final AerospikeClients clients;
    private final EngineTraffic traffic;
    private final AerospikeMetrics metrics;

    @Inject
    AerospikeEngine(AerospikeClients clients, AerospikeMetrics metrics, EngineTraffic traffic) {
        this.traffic = traffic;
        this.clients = clients;
        this.metrics = metrics;
    }

    /**
     * Statistics, which Aerospike does have.
     *
     * <p>Offering this is what turns the monitoring tab back on for an Aerospike target — the
     * capability matrix reads this method rather than a list, so implementing it is the whole of
     * declaring it.
     */
    @Override
    public java.util.Optional<io.keydra.engine.ServerMetrics> metrics() {
        return java.util.Optional.of(metrics);
    }

    @Override
    public EngineType type() {
        return EngineType.AEROSPIKE;
    }

    /**
     * What the server says it is.
     *
     * <p>On a worker thread, because Aerospike's info command is blocking and there is no reactive
     * form of it. Everything else here is reactive and stays on the loop; this one call is the
     * exception and is moved off rather than allowed to sit on an event loop.
     */
    @Override
    public Uni<ServerInfo> describe(ConnectionProfile profile) {
        return OffLoop.call(() -> ask(profile));
    }

    private ServerInfo ask(ConnectionProfile profile) {
        Node[] nodes = command(profile).getAerospikeClient().getNodes();
        if (nodes.length == 0) {
            return new ServerInfo(ServerInfo.FLAVOR_AEROSPIKE, null, ServerInfo.MODE_UNKNOWN);
        }
        Map<String, String> answers = Info.request(null, nodes[0], "build", "edition");
        return new ServerInfo(
                ServerInfo.FLAVOR_AEROSPIKE,
                answers.get("build"),
                // A cluster of one is still a cluster to Aerospike, and saying "standalone" for it
                // would be describing a Redis. What a person wants to know is how many there are.
                nodes.length > 1 ? "cluster" : "standalone");
    }

    /**
     * What this store can do, which is less than a RESP one and says so.
     *
     * <p>Nothing is probed. Every entry below is a fact about Aerospike rather than about the
     * server on the other end — it has no command language whichever version is running — so this
     * is declared rather than detected, and {@code detected} is true because these were not guessed
     * at.
     */
    @Override
    public Uni<Capabilities> capabilities(ConnectionProfile profile) {
        // Expiry is the one thing from the probed half that Aerospike has: a record carries a
        // time to live of its own, and `touch` sets it. Duplicating a key, renaming one, measuring
        // one, a slow log, a client list, streams, pub/sub, cluster slots and sentinels are all
        // things it does not have. The rest comes from `declared`, which reads this engine's own
        // Optionals — and those are empty for everything it cannot serve, so an Aerospike target
        // arrives with most of the interface's tools not offered rather than offered and failing.
        return Uni.createFrom()
                .item(declared(new Capabilities(Set.of(Capabilities.Feature.EXPIRY), true)));
    }

    @Override
    public Multi<KeyEntry> scanKeys(ConnectionProfile profile, KeyQuery query) {
        return scan(profile, query)
                .map(
                        found ->
                                new KeyEntry(
                                        AerospikeKeys.nameOf(found.key),
                                        // Named fields, which is what this application calls a
                                        // hash and what a record actually is.
                                        ONLY_TYPE,
                                        AerospikeKeys.ttlOf(found.record)));
    }

    @Override
    public Multi<String> scanKeyNames(ConnectionProfile profile, KeyQuery query) {
        return scan(profile, query).map(found -> AerospikeKeys.nameOf(found.key));
    }

    /**
     * A walk of the namespace, narrowed to one set where the pattern names one.
     *
     * <p>Aerospike scans a set rather than a keyspace and has no pattern of its own, so the glob is
     * applied here — but the part of it before the first colon is the set, and handing that to the
     * server is the difference between reading one set and reading all of them.
     */
    private Multi<KeyRecord> scan(ConnectionProfile profile, KeyQuery query) {
        // A record is named bins and nothing else, so a filter for any other type is asking this
        // store for something it does not hold. Answering with every record under the wrong label
        // would be worse than answering with none.
        if (query.type() != null && !query.type().isBlank() && !ONLY_TYPE.equals(query.type())) {
            return Multi.createFrom().empty();
        }
        ScanPolicy policy = new ScanPolicy();
        policy.recordsPerSecond = 0;
        String set = AerospikeKeys.setSelectedBy(query.match());
        return Multi.createFrom()
                .publisher(
                        AdaptersToFlow.publisher(
                                command(profile)
                                        .scanAll(policy, profile.namespace, set)
                                        .limitRate(SCAN_BUFFER)))
                .select()
                .where(found -> matches(AerospikeKeys.nameOf(found.key), query.match()));
    }

    /** The glob, applied here because the server has none. */
    private static boolean matches(String name, String glob) {
        if (glob == null || glob.isBlank() || "*".equals(glob)) {
            return true;
        }
        StringBuilder pattern = new StringBuilder();
        for (char c : glob.toCharArray()) {
            switch (c) {
                case '*' -> pattern.append(".*");
                case '?' -> pattern.append('.');
                default -> pattern.append(java.util.regex.Pattern.quote(String.valueOf(c)));
            }
        }
        return name.matches(pattern.toString());
    }

    @Override
    public Uni<Long> deleteKeys(ConnectionProfile profile, List<String> keys) {
        List<Uni<Boolean>> deletions = keys.stream().<Uni<Boolean>>map(name -> null).toList();
        deletions =
                keys.stream()
                        .map(
                                name ->
                                        Uni.createFrom()
                                                .completionStage(
                                                        () ->
                                                                command(profile)
                                                                        .delete(
                                                                                null,
                                                                                AerospikeKeys.keyOf(
                                                                                        profile,
                                                                                        name))
                                                                        .toFuture())
                                                .map(deleted -> true)
                                                .onFailure()
                                                .recoverWithItem(false))
                        .toList();
        if (deletions.isEmpty()) {
            return Uni.createFrom().item(0L);
        }
        return Uni.join()
                .all(deletions)
                .andCollectFailures()
                .map(results -> results.stream().filter(Boolean::booleanValue).count());
    }

    /**
     * Aerospike moves no records, so this does not pretend to.
     *
     * <p>A rename is a read, a write under the new name and a delete of the old one, and doing that
     * silently would turn one operation into three that can each fail on their own — leaving a key
     * in neither place or in both. The store has no rename; the honest answer is that it has none.
     */
    @Override
    public Uni<Long> renameKey(ConnectionProfile profile, String from, String to, boolean replace) {
        return Uni.createFrom()
                .failure(new OperationUnsupportedException("Aerospike has no rename"));
    }

    @Override
    public Uni<Long> copyKey(ConnectionProfile profile, String from, String to, boolean replace) {
        return Uni.createFrom().failure(new OperationUnsupportedException("Aerospike has no copy"));
    }

    @Override
    public Uni<Long> setExpiry(ConnectionProfile profile, String key, Long ttlSeconds) {
        WritePolicy policy = new WritePolicy();
        policy.recordExistsAction = RecordExistsAction.UPDATE_ONLY;
        // Aerospike's own convention, and the reason this is not simply the number: -1 means never
        // expire and -2 means take the namespace's default. Keydra sends null for "no expiry".
        policy.expiration = ttlSeconds == null || ttlSeconds < 0 ? -1 : ttlSeconds.intValue();
        return Uni.createFrom()
                .completionStage(
                        () ->
                                command(profile)
                                        .touch(policy, AerospikeKeys.keyOf(profile, key))
                                        .toFuture())
                .map(touched -> 1L)
                .onFailure()
                .recoverWithItem(0L);
    }

    @Override
    public Uni<ValuePage> readValue(ConnectionProfile profile, ValueQuery query, String encoding) {
        return Uni.createFrom()
                .completionStage(
                        () ->
                                command(profile)
                                        .get(null, AerospikeKeys.keyOf(profile, query.key()))
                                        .toFuture())
                .map(found -> asHash(found, query.key()));
    }

    /**
     * A record's bins, as the named fields they are.
     *
     * <p>A read of a record that is not there answers with a null record rather than an error, and
     * a page of no fields would show it as a key holding nothing — which is what a key holding
     * nothing looks like. So the absence is raised: somebody who opened a key that expired or was
     * deleted while they were reading the list is told that, rather than shown an empty editor.
     */
    private static ValuePage asHash(KeyRecord found, String key) {
        if (found == null || found.record == null) {
            throw new KeyNotFoundException(key);
        }
        List<ValuePage.HashPage.Field> fields = new ArrayList<>();
        found.record.bins.forEach(
                (name, value) -> {
                    String text = String.valueOf(value);
                    fields.add(
                            new ValuePage.HashPage.Field(
                                    name,
                                    new EncodedValue(text, "text", text.getBytes().length, false)));
                });
        return new ValuePage.HashPage(fields, null, (long) fields.size());
    }

    /**
     * Writing one bin, or removing it.
     *
     * <p>Two of the operations this application knows about, and the rest are refused rather than
     * approximated. Pushing onto a list or adding to a sorted set are things Aerospike's data model
     * does have shapes for, but they are shapes inside a bin rather than what a key is — and an
     * editor that quietly turned "add a member" into something else would be worse than one that
     * says the store does not do that.
     *
     * <p>Removing a bin is a write of null to it, which is Aerospike's way of saying "gone". It
     * reads oddly and it is the API.
     */
    @Override
    public Uni<Long> mutateValue(ConnectionProfile profile, ValueMutation mutation) {
        Bin bin =
                switch (mutation) {
                    case ValueMutation.SetHashField set ->
                            new Bin(set.field(), Value.get(set.value()));
                    case ValueMutation.DeleteHashField removed -> Bin.asNull(removed.field());
                    default ->
                            throw new OperationUnsupportedException(
                                    "Aerospike records hold named bins; "
                                            + mutation.getClass().getSimpleName()
                                            + " is not something it can do");
                };
        return Uni.createFrom()
                .completionStage(
                        () ->
                                command(profile)
                                        .put(
                                                new WritePolicy(),
                                                AerospikeKeys.keyOf(profile, mutation.key()),
                                                bin)
                                        .toFuture())
                .map(written -> 1L);
    }

    /**
     * Lets go of a profile's client, which the next use rebuilds.
     *
     * <p>Called when a profile is edited or deleted: a held client points at the host it was built
     * for, so one kept across a change of address goes on talking to the old one.
     */
    /**
     * The client for a profile, counted as the one command about to leave on it.
     *
     * <p>Aerospike has no single place a command passes through the way RESP has a pooled
     * connection, so this is the nearest honest one: <b>every call site below asks for the client
     * and immediately issues exactly one operation with it</b> — a get, a put, a touch, a delete,
     * one scan. That invariant is what makes the count a count of commands rather than of lookups,
     * and this comment is where it stops being true if somebody issues two.
     *
     * <p>{@code deleteKeys} asks per key, which is right: a page of two hundred deletions is two
     * hundred things the server is asked to do.
     */
    private com.aerospike.client.reactor.AerospikeReactorClient command(ConnectionProfile profile) {
        traffic.sent();
        return clients.forProfile(profile);
    }

    @Override
    public void release(Long profileId) {
        clients.forget(profileId);
    }
}
