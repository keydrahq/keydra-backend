package io.keydra.authz.entity;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

/**
 * One thing somebody may do.
 *
 * <p>A closed list rather than a string, because an endpoint has to name the permission it requires
 * and a permission nothing requires protects nothing. Adding one is a compile-time act: the enum
 * grows, the endpoint names it, and the test that walks every endpoint keeps passing.
 *
 * <p>Each carries the scope it can be granted at. {@code users:manage} scoped to one server group
 * would mean nothing, and a model that lets it be written is one that has to explain what it did.
 */
public enum Permission {

    // --- On a connection ---------------------------------------------------

    /** See that the target exists, and its status. */
    CONNECTION_VIEW("connection:view", Level.CONNECTION),
    /** Change where a profile points, and the credentials it uses. */
    CONNECTION_EDIT("connection:edit", Level.CONNECTION),
    CONNECTION_DELETE("connection:delete", Level.CONNECTION),

    KEYS_READ("keys:read", Level.CONNECTION),
    KEYS_WRITE("keys:write", Level.CONNECTION),
    KEYS_DELETE("keys:delete", Level.CONNECTION),

    VALUES_READ("values:read", Level.CONNECTION),
    VALUES_WRITE("values:write", Level.CONNECTION),

    CONSOLE_RUN("console:run", Level.CONNECTION),

    PUBSUB_SUBSCRIBE("pubsub:subscribe", Level.CONNECTION),
    PUBSUB_PUBLISH("pubsub:publish", Level.CONNECTION),

    /** Watch every command the server runs, which is the most revealing thing there is. */
    COMMANDS_WATCH("commands:watch", Level.CONNECTION),

    MONITORING_READ("monitoring:read", Level.CONNECTION),
    MONITORING_MANAGE("monitoring:manage", Level.CONNECTION),
    ANALYSIS_READ("analysis:read", Level.CONNECTION),

    SERVER_READ("server:read", Level.CONNECTION),
    SERVER_CONFIGURE("server:configure", Level.CONNECTION),

    ACL_READ("acl:read", Level.CONNECTION),
    ACL_MANAGE("acl:manage", Level.CONNECTION),

    MIGRATION_RUN("migration:run", Level.CONNECTION),
    /**
     * Arranging for work to happen to this target later.
     *
     * <p>Separate from the permission the work itself needs, and required as well as it: a schedule
     * is a way of doing something later, not a way of doing something you may not do.
     */
    SCHEDULE_MANAGE("schedule:manage", Level.CONNECTION),
    /**
     * Arranging to be told about this target.
     *
     * <p>Beside {@link #MONITORING_MANAGE} rather than inside it, because a rule outlives the
     * person who wrote it: it keeps the target sampled when nobody is watching and sends messages
     * to somewhere outside. Reading the rules and their history needs nothing beyond being able to
     * see the target, which is how the schedules work and for the same reason.
     */
    ALERT_MANAGE("alert:manage", Level.CONNECTION),
    TRANSFER_EXPORT("transfer:export", Level.CONNECTION),
    TRANSFER_IMPORT("transfer:import", Level.CONNECTION),

    // --- On the instance ---------------------------------------------------

    /**
     * Running a script inside Keydra.
     *
     * <p>On the instance rather than on a target, which is the whole point of it. {@link
     * #CONSOLE_RUN} is about one target because a command runs there; a migration script runs
     * <em>here</em>, in Keydra's own process, which holds every target's credentials and can reach
     * every network Keydra can reach. Granting it on a connection would be describing a boundary
     * the thing does not have.
     *
     * <p>Required as well as {@link #MIGRATION_RUN} on the target, not instead of it — the same
     * shape as {@link #SCHEDULE_MANAGE}: a script is a way of doing something, not a way of doing
     * something you may not do.
     */
    SCRIPT_RUN("script:run", Level.INSTANCE),

    CONNECTION_CREATE("connection:create", Level.INSTANCE),
    GROUPS_MANAGE("groups:manage", Level.INSTANCE),
    USERS_MANAGE("users:manage", Level.INSTANCE),
    GRANTS_MANAGE("grants:manage", Level.INSTANCE),
    IDP_MANAGE("idp:manage", Level.INSTANCE),
    /**
     * Configuring where backups go.
     *
     * <p>About Keydra rather than about a target, and deliberately not what an operator holds: a
     * destination carries credentials to somewhere outside, and somebody who may take a backup of
     * one server is not thereby somebody who may decide that backups leave for a bucket of their
     * choosing. Taking and restoring backups stay {@link #TRANSFER_EXPORT} and {@link
     * #TRANSFER_IMPORT} on the target, which is what those permissions already mean.
     */
    BACKUP_MANAGE("backup:manage", Level.INSTANCE),
    /**
     * Describing the jump hosts.
     *
     * <p>About Keydra rather than about a target, and an administrator's alone: a jump host carries
     * a credential that reaches a whole network, and everything Keydra holds for everything behind
     * it travels through it. Choosing which tunnel a target uses is part of editing that target,
     * which is {@link #CONNECTION_EDIT}.
     */
    TUNNEL_MANAGE("tunnel:manage", Level.INSTANCE),
    /**
     * Moving every stored credential onto a new key.
     *
     * <p>The most consequential thing this application can be asked to do, and the only permission
     * that is about the secrets rather than about what they unlock. An administrator alone: a
     * rotation rewrites every credential in the instance, and a half-finished one is a database
     * nothing can read.
     */
    /**
     * Deciding where alerts are sent.
     *
     * <p>About Keydra rather than about a target, and an administrator's alone for the reason a
     * backup destination is: it holds a credential to somewhere outside — a channel token, a mail
     * account — and choosing where a server's troubles are announced is not part of watching that
     * server.
     */
    ALERT_DELIVERY_MANAGE("alert-delivery:manage", Level.INSTANCE),
    CRYPTO_ROTATE("crypto:rotate", Level.INSTANCE),
    AUDIT_READ("audit:read", Level.INSTANCE),

    /**
     * Deciding what this installation asks of everybody who signs in.
     *
     * <p>Its own permission rather than folded into {@link #USERS_MANAGE}, because making accounts
     * and setting the terms every account signs in under are different acts with different blast
     * radii: one is about a person, and this one restricts every person at once until they enrol.
     *
     * <p>Whoever holds it can also take it away from themselves — turning a requirement on that
     * they have not met would leave them with no roles at all — which is why the service refuses
     * that rather than the permission model trying to.
     */
    POLICY_MANAGE("policy:manage", Level.INSTANCE),

    /**
     * Reading how Keydra itself is doing: which instances are running, and what they rest on.
     *
     * <p>Not open, and not the same as being signed in. The roster names hosts and the dependency
     * list names what this deployment is built on — which is a map of the installation, and a map
     * is worth more to somebody who should not have one than to most people who should.
     */
    INSTANCE_READ("instance:read", Level.INSTANCE),

    /**
     * Taking an instance out of service, and putting it back.
     *
     * <p>Separate from {@link #INSTANCE_READ} rather than included in it, because reading where the
     * work is and deciding where it goes are not the same act. Reading the roster is something an
     * operator can reasonably be given — it is how somebody working out why a target is slow finds
     * out that four browsers are watching it from one pod. Draining stops an instance taking new
     * traffic and hands its chores to somebody else, which is a change to what the installation
     * does rather than a look at it.
     */
    INSTANCE_DRAIN("instance:drain", Level.INSTANCE);

    /** Where a permission can be granted. */
    public enum Level {
        /** About one target, and grantable on it or on anything containing it. */
        CONNECTION,
        /** About Keydra itself, and grantable nowhere else. */
        INSTANCE
    }

    private final String id;
    private final Level level;

    Permission(String id, Level level) {
        this.id = id;
        this.level = level;
    }

    /** The name this is written as on the wire and in a grant. */
    public String id() {
        return id;
    }

    public Level level() {
        return level;
    }

    public static Optional<Permission> byId(String id) {
        return Arrays.stream(values()).filter(permission -> permission.id.equals(id)).findFirst();
    }

    /** Every permission about a target, which is what a role scoped to one may carry. */
    public static Set<Permission> onConnections() {
        EnumSet<Permission> all = EnumSet.noneOf(Permission.class);
        Arrays.stream(values())
                .filter(permission -> permission.level == Level.CONNECTION)
                .forEach(all::add);
        return all;
    }
}
