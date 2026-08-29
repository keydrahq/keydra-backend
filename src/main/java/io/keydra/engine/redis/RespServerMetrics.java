package io.keydra.engine.redis;

import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.entity.ConnectionType;
import io.keydra.engine.ClientConnection;
import io.keydra.engine.Database;
import io.keydra.engine.KeyQuery;
import io.keydra.engine.KeySize;
import io.keydra.engine.MetricsSample;
import io.keydra.engine.ServerMetrics;
import io.keydra.engine.SlowCommand;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Statistics from a RESP server.
 *
 * <p>Everything here is read-only apart from killing a client and clearing the slow log, both of
 * which a person has to ask for explicitly.
 */
@ApplicationScoped
public class RespServerMetrics implements ServerMetrics {

    /** How many keys are measured per pipeline when sampling sizes. */
    private static final int MEASURE_BATCH = 50;

    private final RespConnectionPool pool;
    private final RespKeyScanner scanner;

    @Inject
    RespServerMetrics(RespConnectionPool pool, RespKeyScanner scanner) {
        this.pool = pool;
        this.scanner = scanner;
    }

    @Override
    public Uni<MetricsSample> sample(ConnectionProfile profile) {
        return pool.send(profile, Request.cmd(Command.INFO))
                .flatMap(
                        response -> {
                            Map<String, Map<String, String>> sections =
                                    RespInfo.parse(response == null ? null : response.toString());
                            return keyTotal(profile, sections)
                                    .map(total -> toSample(sections, total));
                        });
    }

    /**
     * How many keys to report, from whichever source can answer.
     *
     * <p>INFO first, because it is already in hand and costs nothing further. A cluster is asked
     * node by node instead, for the reason {@link #clusterKeys} gives.
     *
     * <p>And then DBSIZE, for a store whose INFO has no keyspace section at all. Garnet is the one
     * that made this necessary: it files its store's figures under {@code # Store_DB_0} — index
     * buckets, log addresses, page counts — and nowhere in them is the one number a person looking
     * at a catalogue wants. It answers DBSIZE exactly, so the round trip is the price of asking a
     * question the INFO does not happen to answer, and it is only paid by the stores that need it.
     *
     * <p>Not a fallback for an empty database: a store that reports a keyspace and omits a database
     * from it is saying that database holds nothing, and {@link RespInfo#keyCount} already returns
     * zero for that. Null means the section was not there at all, which is a different claim.
     */
    private Uni<Long> keyTotal(
            ConnectionProfile profile, Map<String, Map<String, String>> sections) {
        if (profile.type == ConnectionType.CLUSTER) {
            return clusterKeys(profile)
                    .map(
                            total ->
                                    total != null
                                            ? total
                                            : RespInfo.keyCount(sections, profile.database));
        }
        Long reported = RespInfo.keyCount(sections, profile.database);
        if (reported != null) {
            return Uni.createFrom().item(reported);
        }
        return pool.send(profile, Request.cmd(Command.DBSIZE))
                .map(reply -> reply == null ? null : reply.toLong())
                .onFailure()
                .recoverWithItem((Long) null);
    }

    /**
     * How many keys a cluster holds, which is all of them and not one node's.
     *
     * <p>INFO answers from whichever node the client picked, so its keyspace section counts that
     * node alone. A three-master cluster of sixty-four thousand keys reported twenty-four thousand,
     * and everything downstream believed it: the header, the badge beside the database, and the
     * denominator a migration drew its progress against.
     *
     * <p>Nothing rather than a wrong number if the nodes cannot be listed. A count that is absent
     * is drawn as absent; one that is a third of the truth is drawn as a fact.
     */
    private Uni<Long> clusterKeys(ConnectionProfile profile) {
        return scanner.masters(profile)
                .flatMap(
                        masters ->
                                masters.isEmpty()
                                        ? Uni.createFrom().<Long>nullItem()
                                        : Uni.join()
                                                .all(
                                                        masters.stream()
                                                                .map(
                                                                        address ->
                                                                                dbSize(
                                                                                        profile,
                                                                                        address))
                                                                .toList())
                                                .andCollectFailures()
                                                .map(
                                                        counts ->
                                                                counts.stream()
                                                                        .mapToLong(Long::longValue)
                                                                        .sum()))
                .onFailure()
                .recoverWithItem((Long) null);
    }

    private Uni<Long> dbSize(ConnectionProfile profile, String address) {
        return pool.sendToNode(profile, address, Request.cmd(Command.DBSIZE))
                .map(reply -> reply == null ? 0L : reply.toLong())
                .onFailure()
                .recoverWithItem(0L);
    }

    /**
     * Where a reading lives when a store does not file it under the name Redis uses.
     *
     * <p>These are the same quantity under another spelling, not an approximation of it — a store
     * that measures something genuinely different is better off reporting nothing, because a
     * dashboard cannot tell a substituted number from a real one and neither can the person reading
     * it.
     *
     * <p>Memory is the one that needed care. Redis' {@code used_memory} is what its allocator holds
     * for data; Garnet reports no such figure, and what it does report is the process' resident
     * size. Those are not the same measurement, but they answer the same question — how much of
     * this machine is this server using — and the alternative was an empty column on every Garnet
     * in the fleet. Its store figures were the tempting wrong answer: {@code total_main_store_size}
     * reads 167 MiB on an empty database, because it is capacity rather than use.
     */
    private static final Map<String, List<String>> ALSO_KNOWN_AS =
            Map.of(
                    "used_memory", List.of("proc_physical_memory_size"),
                    "used_memory_peak", List.of("proc_peak_physical_memory_size"),
                    "keyspace_hits", List.of("total_found"),
                    "keyspace_misses", List.of("total_notfound"));

    /** One reading, under whichever of its names this server happens to use. */
    static Long reading(Map<String, Map<String, String>> sections, String name) {
        Long direct = RespInfo.number(sections, name);
        if (direct != null) {
            return direct;
        }
        for (String alias : ALSO_KNOWN_AS.getOrDefault(name, List.of())) {
            Long found = RespInfo.number(sections, alias);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private static MetricsSample toSample(
            Map<String, Map<String, String>> sections, Long keyCount) {
        return new MetricsSample(
                Instant.now(),
                reading(sections, "used_memory"),
                reading(sections, "used_memory_peak"),
                // Zero means "no limit configured", which is not the same as a limit of zero.
                zeroAsNull(reading(sections, "maxmemory")),
                reading(sections, "connected_clients"),
                reading(sections, "instantaneous_ops_per_sec"),
                reading(sections, "total_commands_processed"),
                reading(sections, "keyspace_hits"),
                reading(sections, "keyspace_misses"),
                keyCount,
                reading(sections, "uptime_in_seconds"),
                reading(sections, "evicted_keys"),
                reading(sections, "expired_keys"));
    }

    private static Long zeroAsNull(Long value) {
        return value == null || value == 0 ? null : value;
    }

    @Override
    public Uni<Map<String, Map<String, String>>> info(ConnectionProfile profile, String section) {
        Request request = Request.cmd(Command.INFO);
        if (section != null && !section.isBlank()) {
            request.arg(section);
        }
        return pool.send(profile, request)
                .map(response -> RespInfo.parse(response == null ? null : response.toString()));
    }

    /** What a server answers when it has not been told how many databases to keep. */
    private static final int DEFAULT_DATABASES = 16;

    @Override
    public Uni<List<Database>> databases(ConnectionProfile profile) {
        // How many there are comes from the configuration and what is in them from INFO:
        // the keyspace section only lists databases that hold something, and a list that
        // hides the empty ones cannot be used to move into one.
        return pool.send(profile, Request.cmd(Command.CONFIG).arg("GET").arg("databases"))
                .onFailure()
                // A server that will not answer CONFIG — a managed one, usually — still has
                // databases; it just will not say how many, so the RESP default is assumed.
                .recoverWithItem((Response) null)
                .flatMap(
                        configured ->
                                info(profile, "keyspace")
                                        .map(keyspace -> toDatabases(count(configured), keyspace)));
    }

    /**
     * How many databases CONFIG said there are.
     *
     * <p>RESP2 answers a flat name/value pair and RESP3 a map, so the value is looked up by name
     * first and read positionally only when there is no map to look it up in.
     */
    private static int count(Response configured) {
        if (configured == null) {
            return DEFAULT_DATABASES;
        }
        try {
            Response value =
                    configured.getKeys() != null
                            ? configured.get("databases")
                            : configured.size() < 2 ? null : configured.get(1);
            return value == null ? DEFAULT_DATABASES : Integer.parseInt(value.toString());
        } catch (NumberFormatException | IndexOutOfBoundsException unusable) {
            return DEFAULT_DATABASES;
        }
    }

    /**
     * Turns INFO's keyspace section into one entry per database.
     *
     * <p>The section reads {@code db0:keys=12,expires=3,avg_ttl=0}, and only for databases holding
     * something. Everything else is answered as empty rather than left out.
     */
    private static List<Database> toDatabases(
            int configured, Map<String, Map<String, String>> info) {
        Map<String, String> keyspace = info.getOrDefault("keyspace", Map.of());
        List<Database> databases = new ArrayList<>(configured);
        for (int index = 0; index < configured; index++) {
            String line = keyspace.get("db" + index);
            databases.add(new Database(index, field(line, "keys"), field(line, "expires")));
        }
        return databases;
    }

    /** One {@code name=value} out of a comma-separated line, or zero when it is not there. */
    private static long field(String line, String name) {
        if (line == null) {
            return 0;
        }
        for (String part : line.split(",")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && pair[0].trim().equals(name)) {
                try {
                    return Long.parseLong(pair[1].trim());
                } catch (NumberFormatException notANumber) {
                    return 0;
                }
            }
        }
        return 0;
    }

    @Override
    public Uni<List<SlowCommand>> slowCommands(ConnectionProfile profile, int limit) {
        return pool.send(profile, Request.cmd(Command.SLOWLOG).arg("GET").arg(limit))
                .map(RespServerMetrics::toSlowCommands);
    }

    /**
     * Reads SLOWLOG GET's fixed-position reply.
     *
     * <p>Each entry is id, unix timestamp, duration in microseconds, the arguments, and — since
     * Redis 4 — the client address and name. The last two are read only when present, so an older
     * server reports what it has rather than failing.
     */
    private static List<SlowCommand> toSlowCommands(Response response) {
        if (response == null) {
            return List.of();
        }
        List<SlowCommand> commands = new ArrayList<>(response.size());
        for (int i = 0; i < response.size(); i++) {
            Response entry = response.get(i);
            if (entry == null || entry.size() < 4) {
                continue;
            }
            List<String> arguments = new ArrayList<>();
            entry.get(3).forEach(argument -> arguments.add(argument.toString()));
            commands.add(
                    new SlowCommand(
                            entry.get(0).toLong(),
                            Instant.ofEpochSecond(entry.get(1).toLong()),
                            entry.get(2).toLong(),
                            arguments,
                            entry.size() > 4 ? entry.get(4).toString() : null,
                            entry.size() > 5 ? entry.get(5).toString() : null));
        }
        return commands;
    }

    @Override
    public Uni<Void> clearSlowCommands(ConnectionProfile profile) {
        return pool.send(profile, Request.cmd(Command.SLOWLOG).arg("RESET")).replaceWithVoid();
    }

    @Override
    public Uni<List<ClientConnection>> clients(ConnectionProfile profile) {
        return pool.send(profile, Request.cmd(Command.CLIENT).arg("LIST"))
                .map(response -> toClients(response == null ? null : response.toString()));
    }

    /**
     * Reads CLIENT LIST's one-line-per-client, space-separated {@code key=value} format.
     *
     * <p>Unknown fields are ignored rather than causing a failure: the set grows with each server
     * version, and a client list that breaks on an unfamiliar field would break on every upgrade.
     */
    private static List<ClientConnection> toClients(String listing) {
        if (listing == null || listing.isBlank()) {
            return List.of();
        }
        List<ClientConnection> clients = new ArrayList<>();
        for (String line : listing.split("\\r?\\n")) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, String> fields = new LinkedHashMap<>();
            for (String pair : line.trim().split(" ")) {
                int separator = pair.indexOf('=');
                if (separator > 0) {
                    fields.put(pair.substring(0, separator), pair.substring(separator + 1));
                }
            }
            clients.add(
                    new ClientConnection(
                            fields.get("id"),
                            fields.get("addr"),
                            emptyAsNull(fields.get("name")),
                            parseLong(fields.get("age")),
                            parseLong(fields.get("idle")),
                            parseInt(fields.get("db")),
                            emptyAsNull(fields.get("cmd"))));
        }
        return clients;
    }

    private static String emptyAsNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static Long parseLong(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    private static Integer parseInt(String value) {
        Long parsed = parseLong(value);
        return parsed == null ? null : parsed.intValue();
    }

    @Override
    public Uni<Boolean> killClient(ConnectionProfile profile, String clientId) {
        return pool.send(profile, Request.cmd(Command.CLIENT).arg("KILL").arg("ID").arg(clientId))
                // Answers how many were killed; zero means there was no such client.
                .map(response -> response != null && response.toLong() > 0);
    }

    @Override
    public Multi<KeySize> measureKeys(ConnectionProfile profile, int sampleSize) {
        // MEMORY USAGE is one round trip per key, so the walk is batched and the sample is
        // bounded: measuring a million keys would cost far more than the ranking is worth.
        return scanner.names(profile, new KeyQuery(null, MEASURE_BATCH, null))
                .select()
                .first(sampleSize)
                .group()
                .intoLists()
                .of(MEASURE_BATCH)
                .onItem()
                .transformToUniAndConcatenate(batch -> measure(profile, batch))
                .flatMap(sizes -> Multi.createFrom().iterable(sizes));
    }

    /** Sizes one batch of keys in a single pipeline. */
    private Uni<List<KeySize>> measure(ConnectionProfile profile, List<String> keys) {
        // Size, shape and remaining life together: three round trips per key would cost
        // more than the whole report is worth, and one pipeline costs one.
        List<Request> requests = new ArrayList<>(keys.size() * 3);
        for (String key : keys) {
            requests.add(Request.cmd(Command.MEMORY).arg("USAGE").arg(key));
            requests.add(Request.cmd(Command.TYPE).arg(key));
            requests.add(Request.cmd(Command.PTTL).arg(key));
        }
        return pool.batch(profile, requests)
                .map(
                        responses -> {
                            List<KeySize> sizes = new ArrayList<>(keys.size());
                            for (int i = 0; i < keys.size(); i++) {
                                Response usage = responses.get(i * 3);
                                Response type = responses.get(i * 3 + 1);
                                Response ttl = responses.get(i * 3 + 2);
                                // A key that expired between the scan and here reports no size.
                                if (usage != null) {
                                    sizes.add(
                                            new KeySize(
                                                    keys.get(i),
                                                    type == null ? "none" : type.toString(),
                                                    usage.toLong(),
                                                    null,
                                                    ttl == null
                                                            ? KeySize.NO_EXPIRY
                                                            : ttl.toLong()));
                                }
                            }
                            return sizes;
                        });
    }
}
