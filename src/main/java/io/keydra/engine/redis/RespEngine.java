package io.keydra.engine.redis;

import io.keydra.connections.dto.ServerInfo;
import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.engine.AccessControl;
import io.keydra.engine.Capabilities;
import io.keydra.engine.CommandConsole;
import io.keydra.engine.CommandStream;
import io.keydra.engine.EngineType;
import io.keydra.engine.KeyQuery;
import io.keydra.engine.KeyTransfer;
import io.keydra.engine.KeyValueEngine;
import io.keydra.engine.MessageBus;
import io.keydra.engine.ServerAdmin;
import io.keydra.engine.ServerMetrics;
import io.keydra.engine.Topology;
import io.keydra.keys.dto.KeyEntry;
import io.keydra.keys.exception.KeyNotFoundException;
import io.keydra.values.dto.ValueMutation;
import io.keydra.values.dto.ValuePage;
import io.keydra.values.dto.ValueQuery;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.RedisAPI;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Keydra's engine for stores that speak RESP: Redis, Valkey and the compatible forks.
 *
 * <p>Every RESP command in the application lives here. Above this class the rest of Keydra sees
 * only {@link KeyEntry} and {@link ServerInfo}, so a store with another protocol is a new {@link
 * KeyValueEngine} rather than a change to the services.
 */
@ApplicationScoped
public class RespEngine implements KeyValueEngine {

    /** Nothing here is worth making a caller wait on indefinitely. */
    static final Duration PROBE_TIMEOUT = Duration.ofSeconds(5);

    /** Redis' own "iteration finished" cursor. */
    static final String CURSOR_END = "0";

    private final RespConnectionPool pool;
    private final RespCommandConsole commandConsole;
    private final RespMessageBus messageBus;
    private final RespKeyspaceEvents keyspaceEvents;
    private final RespCommandStream commandStream;
    private final RespServerAdmin serverAdmin;
    private final RespKeyScanner scanner;
    private final RespServerMetrics serverMetrics;
    private final RespCapabilities capabilities;
    private final RespTopology topology;
    private final RespAccessControl accessControl;
    private final RespKeyTransfer keyTransfer;
    private final RespValueReader valueReader;
    private final RespValueWriter valueWriter;

    @Inject
    RespEngine(
            RespConnectionPool pool,
            RespValueReader valueReader,
            RespValueWriter valueWriter,
            RespCommandConsole commandConsole,
            RespMessageBus messageBus,
            RespCommandStream commandStream,
            RespServerAdmin serverAdmin,
            RespKeyspaceEvents keyspaceEvents,
            RespKeyScanner scanner,
            RespServerMetrics serverMetrics,
            RespCapabilities capabilities,
            RespTopology topology,
            RespAccessControl accessControl,
            RespKeyTransfer keyTransfer) {
        this.pool = pool;
        this.valueReader = valueReader;
        this.valueWriter = valueWriter;
        this.commandConsole = commandConsole;
        this.messageBus = messageBus;
        this.keyspaceEvents = keyspaceEvents;
        this.commandStream = commandStream;
        this.serverAdmin = serverAdmin;
        this.scanner = scanner;
        this.serverMetrics = serverMetrics;
        this.capabilities = capabilities;
        this.topology = topology;
        this.accessControl = accessControl;
        this.keyTransfer = keyTransfer;
    }

    @Override
    public Optional<AccessControl> accessControl() {
        // Redis 6 and later keep users; older ones answer with an empty list, which
        // describes them accurately.
        return Optional.of(accessControl);
    }

    @Override
    public Optional<KeyTransfer> transfer() {
        // DUMP and RESTORE have been in Redis since 2.6 and are in every fork that claims
        // RESP compatibility.
        return Optional.of(keyTransfer);
    }

    @Override
    public Optional<Topology> topology() {
        // Every RESP server answers CLUSTER and SENTINEL, even if only to say it is in
        // neither arrangement — which is itself the answer the topology view needs.
        return Optional.of(topology);
    }

    @Override
    public Uni<Capabilities> capabilities(ConnectionProfile profile) {
        // The mode comes from the same INFO the status probe already reads, so this asks
        // for it rather than assuming standalone.
        //
        // Through `declared` on the way out: probing tells us what this *server* has, and the
        // engine's own Optionals say what this *store* offers at all. Both are true of a target and
        // only one of them needs a round trip.
        return describe(profile)
                .flatMap(info -> capabilities.detect(profile, info))
                .map(this::declared);
    }

    @Override
    public Optional<ServerMetrics> metrics() {
        // Every RESP server answers INFO, SLOWLOG and CLIENT LIST.
        return Optional.of(serverMetrics);
    }

    @Override
    public Optional<MessageBus> messaging() {
        // RESP has carried pub/sub since 2.0, so every store speaking it has one.
        return Optional.of(messageBus);
    }

    @Override
    public Optional<io.keydra.engine.KeyspaceEvents> keyspaceEvents() {
        // Redis has published its own mutations since 2.8, and every fork that speaks RESP does
        // the same. Whether this particular server is *set* to publish them is a different
        // question, asked of the server rather than answered here — a capability says the store
        // has the facility, and the facility being switched off is a setting.
        return Optional.of(keyspaceEvents);
    }

    @Override
    public Optional<ServerAdmin> admin() {
        // Every RESP server answers CONFIG, BGSAVE and BGREWRITEAOF.
        return Optional.of(serverAdmin);
    }

    @Override
    public Optional<CommandStream> commandStream() {
        // RESP has had MONITOR since 1.0, so every store speaking it can be watched.
        return Optional.of(commandStream);
    }

    @Override
    public Optional<CommandConsole> console() {
        // RESP is a command language, so every store speaking it has a console.
        return Optional.of(commandConsole);
    }

    @Override
    public EngineType type() {
        return EngineType.RESP;
    }

    @Override
    public Uni<ServerInfo> describe(ConnectionProfile profile) {
        // A throwaway client, so probing an unsaved profile leaves no trace in the pool.
        // Asked for asynchronously because the profile may need a tunnel opened first.
        return pool.createUnpooled(profile)
                .flatMap(
                        client ->
                                Uni.createFrom()
                                        .completionStage(() -> client.connect().toCompletionStage())
                                        .flatMap(
                                                connection ->
                                                        Uni.createFrom()
                                                                .completionStage(
                                                                        () ->
                                                                                RedisAPI.api(
                                                                                                connection)
                                                                                        .info(
                                                                                                List
                                                                                                        .of())
                                                                                        .toCompletionStage())
                                                                .onTermination()
                                                                .invoke(connection::close))
                                        .map(
                                                response ->
                                                        RespServerInfo.parse(
                                                                response == null
                                                                        ? null
                                                                        : response.toString()))
                                        .ifNoItem()
                                        .after(PROBE_TIMEOUT)
                                        .failWith(
                                                () ->
                                                        new IllegalStateException(
                                                                "Timed out after " + PROBE_TIMEOUT))
                                        .onTermination()
                                        .invoke(client::close));
    }

    /** One cursor step: the keys it returned and where to resume. */
    private record Page(String cursor, List<KeyEntry> entries) {}

    @Override
    public Multi<KeyEntry> scanKeys(ConnectionProfile profile, KeyQuery query) {
        AtomicReference<String> cursor = new AtomicReference<>(CURSOR_END);
        return Multi.createBy()
                .repeating()
                .uni(
                        () -> cursor,
                        state ->
                                page(profile, state.get(), query)
                                        .invoke(p -> state.set(p.cursor())))
                // The final page carries cursor 0; emit it, then stop.
                .whilst(page -> !CURSOR_END.equals(page.cursor()))
                .flatMap(page -> Multi.createFrom().iterable(page.entries()));
    }

    @Override
    public Multi<String> scanKeyNames(ConnectionProfile profile, KeyQuery query) {
        return scanner.names(profile, query);
    }

    /**
     * One SCAN step plus the pipeline that describes what it found.
     *
     * <p>SCAN, never KEYS: KEYS blocks the server for the length of the keyspace, which on the
     * datasets this has to handle means stalling every other client.
     */
    private Uni<Page> page(ConnectionProfile profile, String cursor, KeyQuery query) {
        return pool.send(profile, RespKeyScanner.request(cursor, query))
                .flatMap(
                        response -> {
                            String next = response.get(0).toString();
                            List<String> names = RespKeyScanner.toNames(response.get(1));
                            return describe(profile, names).map(entries -> new Page(next, entries));
                        });
    }

    /** Resolves type and TTL for a page of keys in one pipeline. */
    private Uni<List<KeyEntry>> describe(ConnectionProfile profile, List<String> keys) {
        if (keys.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        List<Request> requests = new ArrayList<>(keys.size() * 2);
        for (String key : keys) {
            requests.add(Request.cmd(Command.TYPE).arg(key));
            requests.add(Request.cmd(Command.TTL).arg(key));
        }

        return pool.batch(profile, requests).map(responses -> toEntries(keys, responses));
    }

    private static List<KeyEntry> toEntries(List<String> keys, List<Response> responses) {
        List<KeyEntry> entries = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            Response typeResponse = responses.get(i * 2);
            Response ttlResponse = responses.get(i * 2 + 1);
            String keyType = typeResponse == null ? "none" : typeResponse.toString();
            // A key can expire between SCAN and this pipeline; drop it rather than
            // showing a row for something that no longer exists.
            if (!"none".equals(keyType)) {
                long ttl = ttlResponse == null ? KeyEntry.MISSING : ttlResponse.toLong();
                entries.add(new KeyEntry(keys.get(i), keyType, ttl));
            }
        }
        return entries;
    }

    @Override
    public Uni<Long> deleteKeys(ConnectionProfile profile, List<String> keys) {
        Request del = Request.cmd(Command.DEL);
        keys.forEach(del::arg);
        return pool.send(profile, del).map(RespEngine::count);
    }

    @Override
    public Uni<Long> renameKey(ConnectionProfile profile, String from, String to, boolean replace) {
        Command command = replace ? Command.RENAME : Command.RENAMENX;
        return pool.send(profile, Request.cmd(command).arg(from).arg(to))
                .map(RespEngine::renamedCount);
    }

    @Override
    public Uni<Long> copyKey(ConnectionProfile profile, String from, String to, boolean replace) {
        Request copy = Request.cmd(Command.COPY).arg(from).arg(to);
        if (replace) {
            copy.arg("REPLACE");
        }
        // COPY answers 1 or 0; without REPLACE, 0 means the destination existed.
        return pool.send(profile, copy).map(response -> response == null ? 0L : response.toLong());
    }

    @Override
    public Uni<Long> setExpiry(ConnectionProfile profile, String key, Long ttlSeconds) {
        Request command =
                ttlSeconds == null
                        ? Request.cmd(Command.PERSIST).arg(key)
                        : Request.cmd(Command.EXPIRE).arg(key).arg(ttlSeconds);
        return pool.send(profile, command).map(RespEngine::count);
    }

    /**
     * Reads a value page.
     *
     * <p>The key's type is resolved first, because every type is read with a different command and
     * paged in a different way. It also catches the case where the key expired between being listed
     * and being opened.
     */
    @Override
    public Uni<ValuePage> readValue(ConnectionProfile profile, ValueQuery query, String encoding) {
        return pool.send(profile, Request.cmd(Command.TYPE).arg(query.key()))
                .flatMap(
                        response -> {
                            String type = response == null ? "none" : response.toString();
                            if ("none".equals(type)) {
                                return Uni.createFrom()
                                        .failure(new KeyNotFoundException(query.key()));
                            }
                            return valueReader.read(profile, type, query, encoding);
                        });
    }

    @Override
    public Uni<Long> mutateValue(ConnectionProfile profile, ValueMutation mutation) {
        return valueWriter.write(profile, mutation);
    }

    @Override
    public void release(Long profileId) {
        pool.release(profileId);
    }

    private static long count(Response response) {
        return response == null ? 0 : response.toLong();
    }

    /**
     * Normalises the two shapes a rename can answer with.
     *
     * <p>RENAME replies OK, RENAMENX replies 1 or 0, so the caller sees one number either way.
     */
    private static long renamedCount(Response response) {
        if (response == null) {
            return 0;
        }
        if ("OK".equals(response.toString())) {
            return 1;
        }
        return response.toLong();
    }
}
