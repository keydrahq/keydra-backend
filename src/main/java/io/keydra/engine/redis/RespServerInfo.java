package io.keydra.engine.redis;

import io.keydra.connections.dto.ServerInfo;
import java.util.List;

/**
 * Reads a RESP server's {@code INFO server} section.
 *
 * <p>Parsing lives with the protocol rather than on the DTO, so {@link ServerInfo} stays a neutral
 * description that any engine can produce.
 */
final class RespServerInfo {

    private RespServerInfo() {}

    /**
     * A fork, and the field by which it identifies itself.
     *
     * <p>Ordered, because more than one can match: a fork that reports both its own version and a
     * {@code server_name} is described by the first entry that fits, and the list is written most
     * specific first.
     */
    /**
     * A fork, and how it identifies itself.
     *
     * @param versionField the field carrying its own version, where it has one
     * @param tellField a field only this fork reports, for one that does not
     */
    private record Fork(String flavor, String versionField, String tellField) {}

    /**
     * Every RESP-speaking store Keydra knows how to name.
     *
     * <p>All of them keep {@code redis_version} — compatibility is the point of a fork — and most
     * add a version field of their own. Redis itself is the fallback rather than an entry, because
     * it is the only one with nothing distinguishing to say.
     *
     * <p>KeyDB is the one that had to be looked at rather than assumed. It was listed here as
     * reporting {@code keydb_version} and it does not: a KeyDB 6.3.4 answers {@code
     * redis_version:6.3.4}, sets no {@code server_name}, and was therefore reported as an old Redis
     * — which is exactly the failure this list exists to prevent, sitting in the list itself. What
     * it does report is {@code server_threads}, because being multi-threaded is the whole point of
     * the fork, and {@code storage_provider} for its FLASH tier. The version field stays listed in
     * case a later build adds one.
     *
     * <p>Every other entry has since been checked against a running server rather than left on the
     * same footing KeyDB's was. Dragonfly publishes {@code dragonfly_version}; Garnet publishes
     * {@code garnet_version} and {@code server_name:garnet} both, so it is named twice over. That
     * leaves nothing here resting on an assumption, which was the point of looking.
     *
     * <p>"Fork" is what this record is called and it is not true of all of them. Valkey and KeyDB
     * are forks of Redis; Dragonfly and Garnet are separate programs — C++ and C# respectively —
     * that speak the same protocol and report a {@code redis_version} to say which dialect of it.
     * What they have in common is the only thing this list needs of them: they answer INFO, and
     * they say who they are in it.
     */
    private static final List<Fork> FORKS =
            List.of(
                    new Fork(ServerInfo.FLAVOR_VALKEY, "valkey_version", null),
                    new Fork(ServerInfo.FLAVOR_KEYDB, "keydb_version", "server_threads"),
                    new Fork(ServerInfo.FLAVOR_DRAGONFLY, "dragonfly_version", null),
                    new Fork(ServerInfo.FLAVOR_GARNET, "garnet_version", null));

    /**
     * Decides flavor and version.
     *
     * <p>A fork's own version field is read first and the compatibility field second. Backwards,
     * this reports every Valkey, KeyDB, Dragonfly and Garnet as an old Redis — which is how a UI
     * ends up hiding features the server actually has, or offering ones it does not.
     *
     * <p>{@code server_name} is checked as well: Valkey sets it, and a future fork may set it
     * without adding a version field of its own. And where a fork announces itself by neither —
     * KeyDB does neither — a field only it reports stands in.
     */
    static ServerInfo parse(String info) {
        String serverName = field(info, "server_name");
        String redisVersion = field(info, "redis_version");
        String mode = field(info, "redis_mode");

        for (Fork fork : FORKS) {
            String version = field(info, fork.versionField());
            boolean told = fork.tellField() != null && field(info, fork.tellField()) != null;
            if (version != null || told || fork.flavor().equalsIgnoreCase(serverName)) {
                return new ServerInfo(
                        fork.flavor(),
                        version != null ? version : redisVersion,
                        mode != null ? mode : ServerInfo.MODE_UNKNOWN);
            }
        }

        if (redisVersion != null) {
            return new ServerInfo(
                    ServerInfo.FLAVOR_REDIS,
                    redisVersion,
                    mode != null ? mode : ServerInfo.MODE_UNKNOWN);
        }
        return new ServerInfo(
                ServerInfo.FLAVOR_UNKNOWN, null, mode != null ? mode : ServerInfo.MODE_UNKNOWN);
    }

    private static String field(String info, String key) {
        if (info == null) {
            return null;
        }
        for (String line : info.split("\\r?\\n")) {
            if (line.startsWith(key + ":")) {
                String value = line.substring(key.length() + 1).trim();
                return value.isEmpty() ? null : value;
            }
        }
        return null;
    }
}
