package io.keydra.engine.tikv;

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
import io.keydra.values.dto.EncodedValue;
import io.keydra.values.dto.ValueMutation;
import io.keydra.values.dto.ValuePage;
import io.keydra.values.dto.ValueQuery;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import org.tikv.kvproto.Kvrpcpb;
import org.tikv.raw.RawKVClient;
import org.tikv.shade.com.google.protobuf.ByteString;

/**
 * TiKV: a flat keyspace of bytes, and very little else.
 *
 * <p>Its keys fit this application better than Aerospike's do — they are strings of bytes, so the
 * namespace tree and prefix globs work with no translation at all, and a glob's literal beginning
 * becomes the range scan TiKV actually offers. What it has none of is everything above that: no
 * types, so every key is reported as a string; no statistics; no command language; no pub/sub.
 *
 * <p><strong>It is never asked for a key's time-to-live.</strong> Not as a preference — asking a
 * TiKV that was not started with TTL enabled does not answer an error, it panics and the process
 * dies. That was found by asking one. So this engine does not, and the expiry column is empty for
 * every TiKV key: a deliberate refusal rather than a gap, and the same reason {@code setExpiry} is
 * turned down instead of attempted.
 *
 * <p>Everything the client offers is blocking, so everything here runs on a worker thread. Its scan
 * answers with a materialised list rather than a cursor, so a walk of the keyspace is a loop of
 * ranged reads that carries its own place — which is what keeps peak memory to one page however
 * large the keyspace is.
 */
@ApplicationScoped
public class TikvEngine implements KeyValueEngine {

    /** How many keys one ranged read asks for, which is also how much is held at once. */
    private static final int PAGE = 500;

    /** What Keydra spells for a key that does not expire, and what every key here reports. */
    private static final long NO_EXPIRY = -1;

    /** The only type this store has: a value is bytes, and bytes are what a string is here. */
    private static final String ONLY_TYPE = "string";

    private final TikvSessions sessions;
    private final EngineTraffic traffic;

    @Inject
    TikvEngine(TikvSessions sessions, EngineTraffic traffic) {
        this.traffic = traffic;
        this.sessions = sessions;
    }

    @Override
    public EngineType type() {
        return EngineType.TIKV;
    }

    /**
     * That the cluster answers, which is as much as the raw client will say.
     *
     * <p>There is no version to report: the raw API talks to stores and the placement driver knows
     * the versions, and this client does not expose that. Reaching it at all is the answer.
     */
    @Override
    public Uni<ServerInfo> describe(ConnectionProfile profile) {
        return offLoop(
                profile,
                client -> {
                    client.get(ByteString.copyFromUtf8("keydra:probe"));
                    return new ServerInfo(ServerInfo.FLAVOR_TIKV, null, "cluster");
                });
    }

    /**
     * What this store can do, which is browsing and writing and nothing else.
     *
     * <p>Declared rather than detected: none of these is a property of the server on the other end.
     * `declared` then strips everything this engine does not implement, which is most of it.
     */
    @Override
    public Uni<Capabilities> capabilities(ConnectionProfile profile) {
        return Uni.createFrom().item(declared(new Capabilities(Set.of(), true)));
    }

    @Override
    public Multi<KeyEntry> scanKeys(ConnectionProfile profile, KeyQuery query) {
        // Every value here is bytes; there is no type to report and no type to filter on.
        return scanNames(profile, query).map(name -> new KeyEntry(name, ONLY_TYPE, NO_EXPIRY));
    }

    @Override
    public Multi<String> scanKeyNames(ConnectionProfile profile, KeyQuery query) {
        return scanNames(profile, query);
    }

    /**
     * A walk of the keyspace, one ranged read at a time.
     *
     * <p>The glob's literal beginning is handed to the server as a range, so `user:*` reads one
     * stretch of the keyspace rather than all of it; the rest of the pattern is applied to what
     * comes back, because TiKV applies nothing. Each read starts one key past where the last one
     * ended, which is what makes this a walk rather than one enormous answer.
     */
    private Multi<String> scanNames(ConnectionProfile profile, KeyQuery query) {
        // Every value here is bytes, which this application calls a string. Asked for any other
        // type, the honest answer is that this store holds none — not a list of everything it
        // holds with the wrong label on it.
        if (query.type() != null && !query.type().isBlank() && !ONLY_TYPE.equals(query.type())) {
            return Multi.createFrom().empty();
        }
        ByteString end = TikvRanges.endOf(query.match());
        return Multi.createBy()
                .repeating()
                .uni(
                        () ->
                                new java.util.concurrent.atomic.AtomicReference<>(
                                        TikvRanges.startOf(query.match())),
                        cursor ->
                                offLoop(profile, client -> client.scan(cursor.get(), end, PAGE))
                                        .invoke(page -> cursor.set(after(page, cursor.get()))))
                .whilst(page -> page.size() == PAGE)
                .flatMap(page -> Multi.createFrom().iterable(page))
                .map(pair -> pair.getKey().toStringUtf8())
                .select()
                .where(name -> TikvRanges.matches(name, query.match()));
    }

    /** Where the next read starts: one byte past the last key this one returned. */
    private static ByteString after(List<Kvrpcpb.KvPair> page, ByteString current) {
        if (page.isEmpty()) {
            return current;
        }
        return page.get(page.size() - 1).getKey().concat(ByteString.copyFrom(new byte[] {0}));
    }

    @Override
    public Uni<Long> deleteKeys(ConnectionProfile profile, List<String> keys) {
        return offLoop(
                profile,
                client -> {
                    long removed = 0;
                    for (String key : keys) {
                        client.delete(ByteString.copyFromUtf8(key));
                        removed++;
                    }
                    return removed;
                });
    }

    /**
     * TiKV moves nothing, so this does not pretend to.
     *
     * <p>A rename would be a read, a write and a delete, each able to fail on its own and leave a
     * key in neither place or in both. The store has no rename; the honest answer is that it has
     * none.
     */
    @Override
    public Uni<Long> renameKey(ConnectionProfile profile, String from, String to, boolean replace) {
        return Uni.createFrom().failure(new OperationUnsupportedException("TiKV has no rename"));
    }

    @Override
    public Uni<Long> copyKey(ConnectionProfile profile, String from, String to, boolean replace) {
        return Uni.createFrom().failure(new OperationUnsupportedException("TiKV has no copy"));
    }

    /**
     * Refused rather than attempted, for the reason in this class's own documentation.
     *
     * <p>TiKV's expiry lives behind a cluster setting, and reaching for it on a cluster without
     * that setting is not an error — it is a server that stops. Keydra will not take somebody's
     * cluster down to set a timer.
     */
    @Override
    public Uni<Long> setExpiry(ConnectionProfile profile, String key, Long ttlSeconds) {
        return Uni.createFrom()
                .failure(
                        new OperationUnsupportedException(
                                "This TiKV is not asked about expiry: on a cluster without TTL"
                                        + " enabled the question stops the server"));
    }

    /**
     * A value, which is bytes and is read as text.
     *
     * <p>No key-not-found here, and that is measured rather than overlooked. TiKV stores a key
     * whose value is empty and lists it in a scan, but reading it answers exactly what reading a
     * key that was never written answers — the client's own {@code Optional} is empty in both
     * cases, because on the wire they are the same thing. Raising "no such key" for an empty answer
     * would therefore make every deliberately empty key in somebody's list unreadable, to catch the
     * rarer case of one deleted between being listed and being opened. So the empty value is what
     * is shown, which is at least what the store holds.
     */
    @Override
    public Uni<ValuePage> readValue(ConnectionProfile profile, ValueQuery query, String encoding) {
        return offLoop(
                profile,
                client -> {
                    String text =
                            client.get(ByteString.copyFromUtf8(query.key()))
                                    .map(ByteString::toStringUtf8)
                                    .orElse("");
                    return new ValuePage.StringPage(
                            new EncodedValue(
                                    text,
                                    "text",
                                    text.getBytes(StandardCharsets.UTF_8).length,
                                    false));
                });
    }

    @Override
    public Uni<Long> mutateValue(ConnectionProfile profile, ValueMutation mutation) {
        if (!(mutation instanceof ValueMutation.SetString set)) {
            return Uni.createFrom()
                    .failure(
                            new OperationUnsupportedException(
                                    "A TiKV value is bytes and nothing else; "
                                            + mutation.getClass().getSimpleName()
                                            + " is not something it can do"));
        }
        return offLoop(
                profile,
                client -> {
                    client.put(
                            ByteString.copyFromUtf8(set.key()),
                            ByteString.copyFromUtf8(set.value()));
                    return 1L;
                });
    }

    @Override
    public void release(Long profileId) {
        sessions.forget(profileId);
    }

    /**
     * Runs one blocking call away from the event loop, on a client of its own.
     *
     * <p>Also where the call is counted. Everything this engine asks of a cluster passes through
     * here, which makes it the same place {@code RespConnectionPool.send} is: the last point at
     * which one command is still one command, and counting above it would mean counting a batch as
     * one and a retry as none.
     */
    private <T> Uni<T> offLoop(ConnectionProfile profile, Function<RawKVClient, T> ask) {
        traffic.sent();
        return OffLoop.call(
                () -> {
                    try (RawKVClient client = sessions.clientFor(profile)) {
                        return ask.apply(client);
                    }
                });
    }
}
