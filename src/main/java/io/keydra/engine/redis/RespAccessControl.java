package io.keydra.engine.redis;

import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.engine.AccessControl;
import io.keydra.engine.AclUser;
import io.smallrye.mutiny.Uni;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Redis ACL users.
 *
 * <p>Rules are passed through as the caller wrote them rather than assembled from a form. The rule
 * language is the server's, it grows with each version, and a form that only knows the rules Keydra
 * was written against would quietly prevent anyone from writing the ones it was not.
 *
 * <p>Nothing here returns a password hash. The server will give them out; they are of no use to a
 * UI and of considerable use to an attacker, so they stop at this class.
 */
@ApplicationScoped
public class RespAccessControl implements AccessControl {

    private final RespConnectionPool pool;

    @Inject
    RespAccessControl(RespConnectionPool pool) {
        this.pool = pool;
    }

    @Override
    public Uni<List<AclUser>> users(ConnectionProfile profile) {
        return pool.send(profile, Request.cmd(Command.ACL).arg("LIST"))
                .map(RespAccessControl::toUsers)
                .onFailure()
                // A server from before ACLs exists, and answering "no users" describes it.
                .recoverWithItem(List.of());
    }

    /**
     * Reads {@code ACL LIST}, which answers with one rule line per user.
     *
     * <p>{@code ACL GETUSER} would give structured fields, but costs a round trip per user and
     * includes the password hashes this deliberately never handles. The rule line carries
     * everything the UI shows.
     */
    private static List<AclUser> toUsers(Response response) {
        if (response == null) {
            return List.of();
        }
        List<AclUser> users = new ArrayList<>(response.size());
        response.forEach(
                line -> {
                    List<String> parts = Arrays.asList(line.toString().split(" "));
                    // Every line begins "user <name>"; anything shorter is not one.
                    if (parts.size() < 2) {
                        return;
                    }
                    List<String> rules = parts.subList(2, parts.size());
                    users.add(
                            new AclUser(
                                    parts.get(1),
                                    rules.contains("on"),
                                    rules,
                                    rules.stream().filter(rule -> rule.startsWith("~")).toList(),
                                    rules.stream().filter(rule -> rule.startsWith("&")).toList(),
                                    rules.stream()
                                            .filter(
                                                    rule ->
                                                            rule.startsWith("+")
                                                                    || rule.startsWith("-"))
                                            .reduce((left, right) -> left + " " + right)
                                            .orElse(""),
                                    // A hash is reported as a long hex string after "#".
                                    rules.stream().anyMatch(rule -> rule.startsWith("#"))));
                });
        return users;
    }

    @Override
    public Uni<Void> setUser(ConnectionProfile profile, String username, List<String> rules) {
        Request request = Request.cmd(Command.ACL).arg("SETUSER").arg(username);
        rules.forEach(request::arg);
        return pool.send(profile, request).replaceWithVoid();
    }

    @Override
    public Uni<Boolean> deleteUser(ConnectionProfile profile, String username) {
        return pool.send(profile, Request.cmd(Command.ACL).arg("DELUSER").arg(username))
                .map(response -> response != null && response.toLong() > 0);
    }

    @Override
    public Uni<List<String>> categories(ConnectionProfile profile) {
        return pool.send(profile, Request.cmd(Command.ACL).arg("CAT"))
                .map(
                        response -> {
                            if (response == null) {
                                return List.<String>of();
                            }
                            List<String> categories = new ArrayList<>(response.size());
                            response.forEach(category -> categories.add(category.toString()));
                            return List.copyOf(categories);
                        })
                .onFailure()
                .recoverWithItem(List.of());
    }
}
