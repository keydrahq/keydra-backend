package io.keydra.engine.redis;

import io.keydra.connections.dto.ServerInfo;
import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.engine.Capabilities;
import io.smallrye.mutiny.Uni;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Asks a RESP server which of the commands Keydra depends on it actually has.
 *
 * <p>{@code COMMAND INFO} answers with a null entry for a command the server does not implement,
 * which makes it a direct question rather than an inference. That matters because the forks —
 * KeyDB, Dragonfly, Garnet — all report a {@code redis_version} they are compatible with rather
 * than one that describes what they implement, so version arithmetic gives the wrong answer for
 * exactly the targets this exists to handle.
 *
 * <p>A server that does not implement {@code COMMAND INFO} itself leaves every feature assumed
 * present. Failing at the point of use is better than hiding something the target can do.
 */
@ApplicationScoped
public class RespCapabilities {

    /**
     * One command per feature, chosen as the one that would be called first.
     *
     * <p>Cluster and sentinel are container commands whose availability says nothing about the
     * topology the target is actually in; the mode reported by INFO decides that, and is applied
     * after this map.
     */
    private static final Map<String, String> PROBES = probes();

    private static Map<String, String> probes() {
        Map<String, String> probes = new LinkedHashMap<>();
        probes.put(Capabilities.Feature.COPY_KEY, "copy");
        probes.put(Capabilities.Feature.RENAME_KEY, "rename");
        probes.put(Capabilities.Feature.EXPIRY, "expire");
        probes.put(Capabilities.Feature.MEASURE_MEMORY, "memory");
        probes.put(Capabilities.Feature.SLOW_LOG, "slowlog");
        probes.put(Capabilities.Feature.CLIENT_LIST, "client");
        probes.put(Capabilities.Feature.STREAMS, "xadd");
        probes.put(Capabilities.Feature.PUB_SUB, "subscribe");
        probes.put(Capabilities.Feature.CLUSTER, "cluster");
        probes.put(Capabilities.Feature.SENTINEL, "sentinel");
        probes.put(Capabilities.Feature.METRICS, "info");
        return Map.copyOf(probes);
    }

    private final RespConnectionPool pool;

    @Inject
    RespCapabilities(RespConnectionPool pool) {
        this.pool = pool;
    }

    /**
     * @param mode the topology the server reports, which decides cluster and sentinel
     */
    public Uni<Capabilities> detect(ConnectionProfile profile, String mode) {
        List<String> features = List.copyOf(PROBES.keySet());
        Request request = Request.cmd(Command.COMMAND).arg("INFO");
        features.forEach(feature -> request.arg(PROBES.get(feature)));

        return pool.send(profile, request)
                .map(response -> toCapabilities(features, response, mode))
                .onFailure()
                .recoverWithItem(
                        // The server would not answer the question, so nothing is claimed
                        // about it and every feature stays on offer.
                        Capabilities.assumed());
    }

    private static Capabilities toCapabilities(
            List<String> features, Response response, String mode) {
        if (response == null || response.size() != features.size()) {
            return Capabilities.assumed();
        }

        Set<String> supported = new LinkedHashSet<>(features.size());
        for (int i = 0; i < features.size(); i++) {
            // A null entry is the server saying it has no such command.
            if (response.get(i) != null) {
                supported.add(features.get(i));
            }
        }

        // Having the command is not the same as being in the topology it belongs to: every
        // Redis has CLUSTER, and only a clustered one has anything to say through it.
        if (!"cluster".equalsIgnoreCase(mode)) {
            supported.remove(Capabilities.Feature.CLUSTER);
        }
        if (!"sentinel".equalsIgnoreCase(mode)) {
            supported.remove(Capabilities.Feature.SENTINEL);
        }

        return new Capabilities(Set.copyOf(supported), true);
    }

    /** Convenience for callers holding a {@link ServerInfo} rather than a bare mode. */
    public Uni<Capabilities> detect(ConnectionProfile profile, ServerInfo info) {
        return detect(profile, info == null ? ServerInfo.MODE_UNKNOWN : info.mode());
    }
}
