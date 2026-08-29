package io.keydra.authz.entity;

import java.util.EnumSet;
import java.util.Set;

/**
 * The three roles every deployment has, and what each carries.
 *
 * <p>They cannot be edited. A deployment that needs something between them makes a custom role; a
 * deployment that redefines {@code operator} makes every other deployment's documentation wrong,
 * including this file.
 *
 * <p>Written as code rather than seeded as rows somebody could change: these are the roles the
 * existing token claims map onto, so they have to mean the same thing in every instance.
 */
public enum BuiltInRole {

    /**
     * Reads anything they can see, changes nothing.
     *
     * <p>Includes subscribing, which is a read of a channel rather than a write to it, and excludes
     * the console, because the console's own deny-list is about protecting the server rather than
     * about who is asking — a viewer with a console could still write.
     */
    VIEWER(
            "viewer",
            EnumSet.of(
                    Permission.CONNECTION_VIEW,
                    Permission.KEYS_READ,
                    Permission.VALUES_READ,
                    Permission.PUBSUB_SUBSCRIBE,
                    Permission.MONITORING_READ,
                    Permission.ANALYSIS_READ,
                    Permission.SERVER_READ,
                    Permission.ACL_READ,
                    Permission.TRANSFER_EXPORT)),

    /**
     * Everything a viewer may do, plus changing the data.
     *
     * <p>Not the profile: someone who may edit what is in a target is not thereby someone who may
     * point Keydra at a different one, or read the credentials it would use to get there.
     */
    OPERATOR(
            "operator",
            union(
                    VIEWER.permissions,
                    EnumSet.of(
                            Permission.KEYS_WRITE,
                            Permission.KEYS_DELETE,
                            Permission.VALUES_WRITE,
                            Permission.CONSOLE_RUN,
                            Permission.PUBSUB_PUBLISH,
                            Permission.COMMANDS_WATCH,
                            Permission.MONITORING_MANAGE,
                            Permission.SERVER_CONFIGURE,
                            Permission.ACL_MANAGE,
                            Permission.MIGRATION_RUN,
                            Permission.SCHEDULE_MANAGE,
                            Permission.ALERT_MANAGE,
                            Permission.TRANSFER_IMPORT))),

    /** Everything, including the permissions that are about Keydra rather than about a target. */
    ADMIN("admin", EnumSet.allOf(Permission.class));

    private final String id;
    private final Set<Permission> permissions;

    BuiltInRole(String id, Set<Permission> permissions) {
        this.id = id;
        this.permissions = Set.copyOf(permissions);
    }

    public String id() {
        return id;
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    /**
     * Which of these three a set of permissions amounts to.
     *
     * <p>Keydra's older gate is {@code @RolesAllowed}, which knows three names and nothing about
     * scopes. A person authenticated locally holds permissions rather than a role name, so the
     * names have to be worked out from what they hold, or the coarse gate would refuse everybody
     * the fine one would admit.
     *
     * <p>The rule is "holds something only this role carries": anything a viewer alone can do makes
     * you a viewer, anything an operator adds makes you an operator, anything only an administrator
     * has makes you one. Deliberately not "holds everything the role carries" — a person who may
     * read one server group's keys and nothing else would come out as nobody, and be refused at a
     * door the permission check would have opened.
     */
    public static Set<String> summarise(Set<Permission> held) {
        Set<String> names = new java.util.LinkedHashSet<>();
        if (held.isEmpty()) {
            return names;
        }
        if (anyOf(held, VIEWER.permissions)) {
            names.add(VIEWER.id);
        }
        if (anyOf(held, only(OPERATOR, VIEWER))) {
            names.add(OPERATOR.id);
            names.add(VIEWER.id);
        }
        if (anyOf(held, only(ADMIN, OPERATOR))) {
            names.add(ADMIN.id);
            names.add(OPERATOR.id);
            names.add(VIEWER.id);
        }
        return names;
    }

    /** What the higher role carries and the lower one does not. */
    private static Set<Permission> only(BuiltInRole higher, BuiltInRole lower) {
        EnumSet<Permission> difference = EnumSet.copyOf(higher.permissions);
        difference.removeAll(lower.permissions);
        return difference;
    }

    private static boolean anyOf(Set<Permission> held, Set<Permission> candidates) {
        return candidates.stream().anyMatch(held::contains);
    }

    /** The role a token claim of this name means, if any. */
    public static java.util.Optional<BuiltInRole> byId(String id) {
        return java.util.Arrays.stream(values())
                .filter(role -> role.id.equalsIgnoreCase(id))
                .findFirst();
    }

    private static Set<Permission> union(Set<Permission> left, Set<Permission> right) {
        EnumSet<Permission> both = EnumSet.copyOf(left);
        both.addAll(right);
        return both;
    }
}
