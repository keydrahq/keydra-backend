package io.keydra.engine.redis;

import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.engine.PersistenceState;
import io.keydra.engine.ServerAdmin;
import io.keydra.engine.ServerSetting;
import io.smallrye.mutiny.Uni;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * CONFIG, BGSAVE and BGREWRITEAOF, which is how a RESP server is administered while it runs.
 *
 * <p>RESP has no command that answers "what would this setting have been", so nothing here claims
 * to know a default. What is reported is whether a setting holds anything at all, which for the
 * ones where empty means off is the distinction worth seeing.
 */
@ApplicationScoped
public class RespServerAdmin implements ServerAdmin {

    /**
     * Settings whose value is a secret rather than a setting.
     *
     * <p>Redis answers CONFIG GET requirepass with the password in plain text. Keydra has no
     * business relaying that to a browser, so the value is replaced before it leaves this class —
     * the setting is still listed, because knowing that one is set is the useful part.
     */
    private static final List<String> SECRET =
            List.of("requirepass", "masterauth", "masteruser", "primaryauth");

    private static final String REDACTED = "(set)";

    private final RespConnectionPool pool;
    private final RespServerMetrics metrics;

    @Inject
    RespServerAdmin(RespConnectionPool pool, RespServerMetrics metrics) {
        this.pool = pool;
        this.metrics = metrics;
    }

    @Override
    public Uni<List<ServerSetting>> settings(ConnectionProfile profile, String glob) {
        String pattern = glob == null || glob.isBlank() ? "*" : glob;
        return pool.send(profile, Request.cmd(Command.CONFIG).arg("GET").arg(pattern))
                .map(RespServerAdmin::toSettings);
    }

    @Override
    public Uni<Void> changeSetting(ConnectionProfile profile, String name, String value) {
        return pool.send(profile, Request.cmd(Command.CONFIG).arg("SET").arg(name).arg(value))
                .replaceWithVoid();
    }

    @Override
    public Uni<Void> persistSettings(ConnectionProfile profile) {
        return pool.send(profile, Request.cmd(Command.CONFIG).arg("REWRITE")).replaceWithVoid();
    }

    @Override
    public Uni<PersistenceState> persistence(ConnectionProfile profile) {
        return metrics.info(profile, "persistence")
                .flatMap(
                        info ->
                                snapshotFile(profile)
                                        .map(file -> toPersistence(info, file))
                                        // Where the file goes is a nicety beside whether the
                                        // server is saving at all, so a server that will not
                                        // answer CONFIG loses the path rather than the card.
                                        .onFailure()
                                        .recoverWithItem(() -> toPersistence(info, null)));
    }

    /**
     * Where this server writes its snapshot.
     *
     * <p>Two calls rather than one {@code CONFIG GET dir dbfilename}: several parameters in one
     * CONFIG GET arrived in Redis 7, and Keydra talks to whatever is there.
     */
    private Uni<String> snapshotFile(ConnectionProfile profile) {
        return setting(profile, "dir")
                .flatMap(
                        directory ->
                                setting(profile, "dbfilename")
                                        .map(
                                                name ->
                                                        directory == null || name == null
                                                                ? null
                                                                : directory
                                                                        + (directory.endsWith("/")
                                                                                ? ""
                                                                                : "/")
                                                                        + name));
    }

    /** One CONFIG GET, read out of whichever shape the protocol answered in. */
    private Uni<String> setting(ConnectionProfile profile, String name) {
        return pool.send(profile, Request.cmd(Command.CONFIG).arg("GET").arg(name))
                .map(
                        response -> {
                            List<ServerSetting> settings = toSettings(response);
                            return settings.isEmpty() || settings.get(0).value().isEmpty()
                                    ? null
                                    : settings.get(0).value();
                        });
    }

    @Override
    public Uni<Void> snapshot(ConnectionProfile profile) {
        return pool.send(profile, Request.cmd(Command.BGSAVE)).replaceWithVoid();
    }

    @Override
    public Uni<Void> rewriteLog(ConnectionProfile profile) {
        return pool.send(profile, Request.cmd(Command.BGREWRITEAOF)).replaceWithVoid();
    }

    /**
     * Reads CONFIG GET's answer, whichever shape the protocol gave it.
     *
     * <p>RESP2 answers a flat name, value, name, value sequence and RESP3 a map. The same
     * difference that broke the migration's hashes, and the same handling: look it up as a map when
     * there is one, and read it positionally when there is not.
     */
    private static List<ServerSetting> toSettings(Response response) {
        if (response == null) {
            return List.of();
        }
        Map<String, String> values = new LinkedHashMap<>();
        if (response.getKeys() != null) {
            for (String name : response.getKeys()) {
                Response value = response.get(name);
                values.put(name, value == null ? "" : value.toString());
            }
        } else {
            for (int i = 0; i + 1 < response.size(); i += 2) {
                values.put(response.get(i).toString(), response.get(i + 1).toString());
            }
        }

        List<ServerSetting> settings = new ArrayList<>(values.size());
        values.forEach(
                (name, value) ->
                        settings.add(
                                new ServerSetting(
                                        name,
                                        SECRET.contains(name) && !value.isEmpty()
                                                ? REDACTED
                                                : value,
                                        // Empty is what an unset setting reports, and for the
                                        // ones where empty means off that is worth marking.
                                        value.isEmpty())));
        settings.sort((left, right) -> left.name().compareTo(right.name()));
        return settings;
    }

    private static PersistenceState toPersistence(
            Map<String, Map<String, String>> info, String snapshotFile) {
        Map<String, String> section = info.getOrDefault("persistence", Map.of());
        return new PersistenceState(
                // rdb_bgsave_in_progress exists on every server; rdb_changes_since_last_save
                // is the figure that says whether a snapshot would even do anything.
                !"0".equals(section.getOrDefault("rdb_last_save_time", "0")),
                "1".equals(section.getOrDefault("aof_enabled", "0")),
                number(section.get("rdb_last_save_time")),
                "err".equals(section.getOrDefault("rdb_last_bgsave_status", "ok")),
                number(section.get("rdb_changes_since_last_save")),
                "1".equals(section.getOrDefault("rdb_bgsave_in_progress", "0"))
                        || "1".equals(section.getOrDefault("aof_rewrite_in_progress", "0")),
                snapshotFile);
    }

    private static long number(String value) {
        try {
            return value == null ? 0 : Long.parseLong(value.trim());
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }
}
