package io.keydra.authz.entity;

/**
 * What a grant is about.
 *
 * <p>Three levels, containing each other: a grant on the instance reaches every server group, and a
 * grant on a server group reaches every connection in it and in the groups below it.
 */
public enum ScopeType {
    /** Keydra itself. The only scope the instance permissions can be granted at. */
    INSTANCE,
    /** One server group, and everything under it. */
    SERVER_GROUP,
    /** One target. */
    CONNECTION
}
