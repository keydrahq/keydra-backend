package io.keydra.engine.redis;

import io.keydra.common.tls.Certificates;
import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.entity.ConnectionType;
import io.keydra.engine.EngineTraffic;
import io.keydra.tunnels.TunnelEndpoint;
import io.keydra.tunnels.service.TunnelAccess;
import io.quarkus.runtime.ShutdownEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import io.vertx.core.buffer.Buffer;
import io.vertx.core.net.NetClientOptions;
import io.vertx.core.net.PemKeyCertOptions;
import io.vertx.core.net.PemTrustOptions;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisClientType;
import io.vertx.redis.client.RedisCluster;
import io.vertx.redis.client.RedisOptions;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns every RESP client in the application.
 *
 * <p>The only place {@link Redis#createClient} is called. Clients are opened lazily on first use
 * and reused afterwards, so browsing a target does not reconnect per request.
 *
 * <p>Deliberately confined to this package: RESP request and response types stop here, which is
 * what lets a non-RESP store be added without disturbing anything above the engine.
 */
@ApplicationScoped
public class RespConnectionPool {

    /**
     * How many connections the pool opens to one target.
     *
     * <p>A setting rather than a literal, and eight rather than the four it was, but honestly:
     * against a Redis on the same machine no number here could be told apart from any other. Runs
     * at four, eight and sixteen came out at 2571, 1675 and 2639 keys a second — a spread that is
     * measurement noise, not a trend. What that means is that on a loopback there is no round trip
     * to overlap, so more connections have nothing to do; it says nothing about a target across a
     * network, which is the case the number exists for and the one that cannot be measured from
     * here.
     *
     * <p>So this is not a tuned value. It is a knob with its reasoning written down, set slightly
     * above the old default because more connections cost little and the old one was chosen for a
     * console rather than for a migration.
     */
    private final int poolSize;

    /**
     * How many commands the pool will hold at once before it starts refusing them.
     *
     * <p>A few connections are plenty for a console, but a caller that fans out — a migration
     * restoring a batch, a browser fetching a page of values — puts far more than that in flight
     * and the rest wait for a connection. Vert.x's default queue is twenty-four deep, which a
     * single batch overruns; the pool then answers "Redis waiting queue is full" and the caller
     * records a failure that has nothing to do with the target.
     *
     * <p>It was 128, then 2048, and both were guesses a migration outgrew. What has to fit is
     * arithmetic rather than a round number, so here it is: a migration holds {@code
     * keydra.keys.migration-batch-concurrency} batches at once, each of {@code
     * keydra.keys.transfer-batch-size} keys, and rebuilding one key takes up to three commands —
     * clear it, write it, set its expiry. With the shipped defaults that is 4 x 200 x 3, or 2,400
     * commands in flight, which is how a queue of 2,048 came to refuse a key in a migration that
     * had done nothing wrong. The reading side adds its own on top: two commands per key to ask
     * what each one is and how long it has left.
     *
     * <p>So the default is set well clear of what the defaults produce, and it is a setting because
     * everything it has to exceed is also one. Raising the batch size or how many batches run at
     * once means raising this too, and the relationship is written down here rather than left to be
     * rediscovered from a key marked refused.
     *
     * <p>Depth is cheap. What waits here is a request, not a reply, and the memory a migration
     * actually uses is bounded where it should be: by how many batches of values it holds.
     */
    private final int maxWaiting;

    /**
     * What a caller may fan out to on its own without overrunning the queue.
     *
     * <p>Published so a fan-out is set against the pool's number rather than guessed at. Lower than
     * the queue, because the queue also has to hold whatever else is using the target.
     */
    public static final int MAX_CONCURRENT_COMMANDS = 2048;

    private final Vertx vertx;
    private final TunnelAccess tunnels;

    /**
     * One client per profile and database.
     *
     * <p>A RESP client is bound to a database by its connection string, so "the same target, a
     * different database" is a different client rather than a SELECT on a shared one — which would
     * otherwise leak into whoever used that connection next.
     */
    private record ClientKey(long profileId, int database, String node) {}

    private final Map<ClientKey, Redis> clients = new ConcurrentHashMap<>();

    private final EngineTraffic traffic;

    @Inject
    RespConnectionPool(
            Vertx vertx,
            TunnelAccess tunnels,
            EngineTraffic traffic,
            @org.eclipse.microprofile.config.inject.ConfigProperty(
                            name = "keydra.engine.pool-size",
                            defaultValue = "8")
                    int poolSize,
            @org.eclipse.microprofile.config.inject.ConfigProperty(
                            name = "keydra.engine.max-waiting-commands",
                            defaultValue = "16384")
                    int maxWaiting) {
        this.traffic = traffic;
        this.vertx = vertx;
        this.tunnels = tunnels;
        this.poolSize = Math.max(1, poolSize);
        this.maxWaiting = Math.max(MAX_CONCURRENT_COMMANDS, maxWaiting);
    }

    void onStop(@Observes ShutdownEvent event) {
        Set.copyOf(clients.keySet()).forEach(key -> release(key.profileId()));
    }

    /**
     * Sends one command to one named node of a cluster, rather than to the cluster.
     *
     * <p>For the commands a cluster answers per node instead of as a whole, which is SCAN and the
     * counts built from it. The client's own routing picks a node by the key, and SCAN has no key;
     * it goes wherever the client feels like and returns that node's cursor, so a walk driven by
     * one cursor sees one node's keys and calls it the keyspace.
     *
     * <p>The node gets a client of its own, cached beside the cluster's. Built from a copy of the
     * profile pointed at that address and marked standalone, so the credentials, the TLS settings
     * and the tunnel are the ones the target already has — a second place to configure how to reach
     * a node would be a second place to get it wrong.
     */
    public Uni<Response> sendToNode(ConnectionProfile profile, String address, Request request) {
        return clientForNode(profile, address)
                .flatMap(
                        client ->
                                Uni.createFrom()
                                        .completionStage(
                                                () -> client.send(request).toCompletionStage()));
    }

    private Uni<Redis> clientForNode(ConnectionProfile profile, String address) {
        ClientKey key =
                profile.id == null
                        ? null
                        : new ClientKey(profile.id, profile.effectiveDatabase(), address);
        Redis cached = key == null ? null : clients.get(key);
        if (cached != null) {
            return Uni.createFrom().item(cached);
        }
        ConnectionProfile node = asStandalone(profile, address);
        return tunnels.endpointFor(node)
                .map(
                        endpoint -> {
                            if (key == null) {
                                return create(node, endpoint);
                            }
                            return clients.computeIfAbsent(key, ignored -> create(node, endpoint));
                        });
    }

    /** The same target, addressed as one node of it. Detached: nothing persists this. */
    private static ConnectionProfile asStandalone(ConnectionProfile profile, String address) {
        int colon = address.lastIndexOf(':');
        ConnectionProfile node = new ConnectionProfile();
        node.id = profile.id;
        node.name = profile.name;
        node.host = colon < 0 ? address : address.substring(0, colon);
        node.port = colon < 0 ? profile.port : Integer.parseInt(address.substring(colon + 1));
        node.username = profile.username;
        node.password = profile.password;
        node.tls = profile.tls;
        node.database = profile.database;
        node.selectedDatabase = profile.selectedDatabase;
        node.engine = profile.engine;
        node.flavor = profile.flavor;
        // Standalone on purpose: this is a conversation with one node, not with the cluster.
        node.type = ConnectionType.STANDALONE;
        node.tunnelId = profile.tunnelId;
        return node;
    }

    /**
     * How long to wait before offering a command to a full queue again, and how often.
     *
     * <p>Patient, and it has to be. Three tries fifty milliseconds apart was the first attempt and
     * it assumed the queue fills in bursts; under a migration it does not, it stays full for as
     * long as the migration runs. So the wait doubles each time — fifty milliseconds up to about
     * three seconds in total — which turns a spurious refusal into a caller going slightly slower,
     * which is what back-pressure is asking for. A command that still cannot be queued after three
     * seconds of trying is a real problem and is reported as one.
     */
    private static final int BACK_PRESSURE_RETRIES = 6;

    private static final Duration BACK_PRESSURE_PAUSE = Duration.ofMillis(50);

    /**
     * Whether a failure is the pool declining to queue the command, rather than an answer.
     *
     * <p>This distinction is the whole point. "Redis waiting queue is full" comes from the client
     * before anything reaches the server, and it used to arrive at a migration looking exactly like
     * the target rejecting a key — a key counted as refused, in a run that had done nothing wrong,
     * with a reason that sends somebody to look at their server. It is back-pressure, and the
     * answer to back-pressure is to wait and offer it again.
     *
     * <p>Matched on the message because that is all the client gives: it raises a plain exception
     * with this text rather than a type of its own.
     */
    private static boolean isBackPressure(Throwable failure) {
        for (Throwable at = failure; at != null && at != at.getCause(); at = at.getCause()) {
            String message = at.getMessage();
            if (message != null && message.contains("waiting queue")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Sends one command to every primary in a cluster, and answers what each of them said.
     *
     * <p>For the questions where each node's answer is about itself rather than about the cluster.
     * {@code CLUSTER NODES} is the one that matters: every node describes the same arrangement, but
     * only its own {@code myself} line names the slots it is moving — so a reshard between two
     * other nodes is invisible from where a single connection stands.
     *
     * <p>On the connections the client already holds, in parallel, which is what makes this
     * affordable: it is a round trip per node rather than a connection per node.
     *
     * <p>Counted as what it is — one command per node — for the reason every other count here is
     * where the commands actually leave.
     */
    public Uni<List<Response>> sendToEveryPrimary(ConnectionProfile profile, Request request) {
        return clientFor(profile)
                .flatMap(
                        client -> {
                            RedisCluster cluster = RedisCluster.create(client);
                            return Uni.createFrom()
                                    .completionStage(
                                            () ->
                                                    cluster.onAllMasterNodes(request)
                                                            .toCompletionStage())
                                    .invoke(replies -> traffic.sent(Math.max(1, replies.size())));
                        });
    }

    /** Sends one command, reusing the profile's cached client. */
    public Uni<Response> send(ConnectionProfile profile, Request request) {
        return clientFor(profile)
                .flatMap(
                        client ->
                                Uni.createFrom()
                                        .completionStage(
                                                () -> {
                                                    // Counted here rather than at any of the
                                                    // dozen call sites: this is the last place a
                                                    // command is still one command, and counting
                                                    // above it would mean counting a batch as a
                                                    // command and a retry as nothing.
                                                    traffic.sent();
                                                    return client.send(request).toCompletionStage();
                                                }))
                .onFailure(RespConnectionPool::isBackPressure)
                .retry()
                .withBackOff(BACK_PRESSURE_PAUSE, Duration.ofSeconds(2))
                .atMost(BACK_PRESSURE_RETRIES);
    }

    /**
     * Sends several commands together, and answers their replies in the order they were given.
     *
     * <p>Fetching type and TTL for a page of keys this way is one round trip instead of one per
     * key, which is the difference between browsing a large keyspace and crawling it.
     *
     * <p>Against a cluster it cannot be one round trip, and pretending otherwise is what broke. A
     * pipeline goes to one node, and a cluster refuses one whose keys do not all live in the same
     * slot — "CROSSSLOT ... client side resharding is not supported". Since keys in a page of a
     * keyspace are spread across slots by design, that is every batch rather than an unlucky one:
     * browsing a cluster, and migrating to or from one, failed partway through with a message about
     * hash slots.
     *
     * <p>So a cluster gets the commands sent one at a time, which lets the client route each by its
     * own key, several at once so it is not a crawl, and reassembled into the order they arrived
     * in. Everything above this asked for "these commands, those replies" and still gets exactly
     * that; what changes is how many round trips it costs, and only where it has to.
     */
    public Uni<List<Response>> batch(ConnectionProfile profile, List<Request> requests) {
        if (requests.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        return clientFor(profile)
                .flatMap(
                        client -> {
                            // As many as it holds, not one. A pipeline is a saving in round trips
                            // rather than in commands, and the server does the same amount of work
                            // either way.
                            traffic.sent(requests.size());
                            return profile.type == ConnectionType.CLUSTER
                                    ? byNode(client, requests)
                                    : Uni.createFrom()
                                            .completionStage(
                                                    () ->
                                                            client.batch(requests)
                                                                    .toCompletionStage());
                        })
                .onFailure(RespConnectionPool::isBackPressure)
                .retry()
                .withBackOff(BACK_PRESSURE_PAUSE, Duration.ofSeconds(2))
                .atMost(BACK_PRESSURE_RETRIES);
    }

    /**
     * The same replies, from a cluster, one pipeline per node.
     *
     * <p>{@code groupByNodes} is the client's own answer to the problem: it sorts the commands into
     * the nodes that own their keys, and each of those groups is a pipeline the cluster will
     * accept. So a page of two hundred keys is three round trips on a three-master cluster rather
     * than two hundred, which is the difference between a migration that finishes and one somebody
     * abandons.
     *
     * <p>Order is restored by position rather than assumed. Grouping deliberately reorders — that
     * is the point of it — and every caller reads these replies positionally against the keys it
     * asked about, so an off-by-one would hand somebody another key's type. Each command is looked
     * up by identity, which is exact here because callers build a fresh request per key.
     *
     * <p>A reply can be nil, which is an answer rather than an absence: DUMP of a key that is not
     * there returns one. That is why the array is filled by index instead of collected.
     */
    private Uni<List<Response>> byNode(Redis client, List<Request> requests) {
        Map<Request, Integer> positions = new IdentityHashMap<>(requests.size());
        for (int index = 0; index < requests.size(); index++) {
            positions.put(requests.get(index), index);
        }
        RedisCluster cluster = RedisCluster.create(client);
        return Uni.createFrom()
                .completionStage(() -> cluster.groupByNodes(requests).toCompletionStage())
                .flatMap(
                        groups ->
                                Multi.createFrom()
                                        .iterable(groups)
                                        .filter(group -> !group.isEmpty())
                                        .onItem()
                                        .transformToUni(
                                                group ->
                                                        Uni.createFrom()
                                                                .completionStage(
                                                                        () ->
                                                                                client.batch(group)
                                                                                        .toCompletionStage())
                                                                .map(
                                                                        replies ->
                                                                                new Group(
                                                                                        group,
                                                                                        replies)))
                                        .merge(poolSize)
                                        .collect()
                                        .asList())
                .map(
                        answered -> {
                            Response[] ordered = new Response[requests.size()];
                            for (Group group : answered) {
                                for (int i = 0; i < group.sent().size(); i++) {
                                    Integer at = positions.get(group.sent().get(i));
                                    if (at != null && i < group.replies().size()) {
                                        ordered[at] = group.replies().get(i);
                                    }
                                }
                            }
                            return java.util.Arrays.asList(ordered);
                        });
    }

    /** One node's pipeline and what came back, so replies can be put where they belong. */
    private record Group(List<Request> sent, List<Response> replies) {}

    /**
     * A throwaway client for probing a profile that may not be saved yet, or for a subscription
     * that will occupy its connection.
     *
     * <p>Answers a {@code Uni} because the profile may need a tunnel opened first, which is a
     * network operation and cannot be done on an event loop.
     */
    public Uni<Redis> createUnpooled(ConnectionProfile profile) {
        return tunnels.endpointFor(profile).map(endpoint -> create(profile, endpoint));
    }

    /**
     * Drops the cached client so the next use picks up edited connection settings.
     *
     * <p>Closes the tunnel too. An edited profile may point somewhere else entirely, and a tunnel
     * left open to the old place is a connection to a host nobody asked for any more.
     */
    public void release(Long profileId) {
        if (profileId == null) {
            return;
        }
        // Every database's client, not only the one the profile opens in: an edited profile
        // may point somewhere else entirely and all of them are now stale.
        Set.copyOf(clients.keySet()).stream()
                .filter(key -> key.profileId() == profileId)
                .forEach(
                        key -> {
                            Redis client = clients.remove(key);
                            if (client != null) {
                                client.close();
                            }
                        });
        // The tunnel is not closed with the profile any more. It belongs to a jump host that
        // other targets — and backup destinations — reach through, and dropping it because one
        // of them was edited would take the rest down with it. Closing one is what editing or
        // removing the tunnel does.
    }

    /**
     * The profile's client, opening a tunnel first when the profile needs one.
     *
     * <p>Asynchronous because opening a tunnel is: it costs a TCP connection, a key exchange and an
     * authentication. For a profile with no tunnel the endpoint is answered immediately and this
     * costs a completed Uni.
     */
    private Uni<Redis> clientFor(ConnectionProfile profile) {
        ClientKey key =
                profile.id == null
                        ? null
                        : new ClientKey(profile.id, profile.effectiveDatabase(), null);
        Redis cached = key == null ? null : clients.get(key);
        if (cached != null) {
            return Uni.createFrom().item(cached);
        }
        return tunnels.endpointFor(profile)
                .map(
                        endpoint -> {
                            if (key == null) {
                                // Unsaved profiles have nothing to key a cache on.
                                return create(profile, endpoint);
                            }
                            return clients.computeIfAbsent(
                                    key, ignored -> create(profile, endpoint));
                        });
    }

    private Redis create(ConnectionProfile profile, TunnelEndpoint endpoint) {
        RedisOptions options =
                new RedisOptions()
                        .setType(clientType(profile.type))
                        .setConnectionString(connectionString(profile, endpoint))
                        .setMaxPoolSize(poolSize)
                        .setMaxPoolWaiting(maxWaiting);
        if (profile.type == ConnectionType.SENTINEL && profile.sentinelMasterName != null) {
            options.setMasterName(profile.sentinelMasterName);
        }
        if (profile.tls) {
            options.setNetClientOptions(tlsOptions(profile, endpoint));
        }
        return Redis.createClient(vertx, options);
    }

    /**
     * How TLS is set up for a target.
     *
     * <p>The verification algorithm is not optional and used not to be set, which meant no TLS
     * target could be reached at all: Vert.x refuses to connect rather than guess, and says so with
     * "Missing hostname verification algorithm". Managed Redis is almost always TLS — Upstash,
     * ElastiCache in transit, Azure Cache — so this was every hosted target.
     *
     * <p>{@code HTTPS} is the algorithm's name and not a protocol here. It means the certificate's
     * subject alternative names are checked against the host being dialled, by the rules browsers
     * use. Without it a certificate for any host signed by any trusted authority would be accepted
     * for this one, which is most of what TLS was for.
     *
     * <p>Through a tunnel it is deliberately not checked, and that is the one case worth being
     * plain about. The client dials the local end of a forward — {@code 127.0.0.1} — while the
     * certificate names the target, so no honest certificate can match and checking would refuse
     * every tunnelled TLS target rather than catch anything. What stands in for it is the tunnel
     * itself: the jump host is authenticated by a host key Keydra pins, the hop to the target
     * happens inside that network, and both of those are decisions somebody already made when they
     * described the tunnel. Trust is still not blanket — the certificate must still be signed by a
     * trusted authority — it is only the name that goes unchecked.
     */
    private static NetClientOptions tlsOptions(ConnectionProfile profile, TunnelEndpoint endpoint) {
        NetClientOptions options = new NetClientOptions().setSsl(true).setTrustAll(false);
        options.setHostnameVerificationAlgorithm(endpoint.tunnelled() ? "" : "HTTPS");

        // The authority this target's certificate was signed by, where the JVM's own store does
        // not hold it — which is most of the Redis inside a company. Named on the profile so that
        // trusting it for this target does not mean trusting it for every connection this process
        // makes. Setting it replaces the default store for this client only, which is the point:
        // a private authority is not an addition to the public ones, it is the one that signs
        // this.
        if (profile.tlsCaCert != null && !profile.tlsCaCert.isBlank()) {
            options.setPemTrustOptions(
                    new PemTrustOptions().addCertValue(Buffer.buffer(profile.tlsCaCert)));
        }
        // And what to present when the target asks. Both halves or neither: the profile is refused
        // before it is saved if it holds one of them, so by here they travel together.
        //
        // The key goes through Certificates on the way, which is where a passphrase stops: these
        // options have no field for one, and no longer need one. What arrives here is an ordinary
        // unencrypted key whether the stored one was locked or not, and whether it was written as
        // PKCS#1 or PKCS#8 — the same key Aerospike is handed, by the same road.
        if (profile.tlsClientCert != null && !profile.tlsClientCert.isBlank()) {
            options.setPemKeyCertOptions(
                    new PemKeyCertOptions()
                            .addCertValue(Buffer.buffer(profile.tlsClientCert))
                            .addKeyValue(
                                    Buffer.buffer(
                                            Certificates.unlockedPrivateKey(
                                                    profile.tlsClientKey,
                                                    profile.tlsClientKeyPassphrase))));
        }
        return options;
    }

    private static RedisClientType clientType(ConnectionType type) {
        return switch (type) {
            case STANDALONE -> RedisClientType.STANDALONE;
            case CLUSTER -> RedisClientType.CLUSTER;
            case SENTINEL -> RedisClientType.SENTINEL;
        };
    }

    /**
     * Builds {@code redis[s]://[user:password@]host:port[/db]} for a given endpoint.
     *
     * <p>Credentials are URL-encoded so a password containing {@code @}, {@code :} or {@code /}
     * cannot corrupt the URI. The result contains a secret and is never logged.
     */
    static String connectionString(ConnectionProfile profile, TunnelEndpoint endpoint) {
        StringBuilder uri = new StringBuilder(profile.tls ? "rediss://" : "redis://");
        if (profile.hasCredentials()) {
            if (profile.username != null && !profile.username.isBlank()) {
                uri.append(encode(profile.username));
            }
            uri.append(':').append(encode(profile.password)).append('@');
        }
        // The endpoint, not the profile: through a tunnel these differ, and the client
        // must dial the local end.
        uri.append(endpoint.host()).append(':').append(endpoint.port());
        // Cluster and sentinel have one database, so an index there is meaningless rather
        // than merely unused.
        if (profile.type == ConnectionType.STANDALONE && profile.effectiveDatabase() > 0) {
            uri.append('/').append(profile.effectiveDatabase());
        }
        return uri.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
