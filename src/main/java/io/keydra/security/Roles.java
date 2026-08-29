package io.keydra.security;

/**
 * The three things a person can be to Keydra.
 *
 * <p>Three rather than a permission per operation, because the distinctions that matter here are
 * few and blunt: whether you may change data, and whether you may change how Keydra reaches it. A
 * finer-grained scheme would have to be configured before it meant anything, and an unconfigured
 * permission system is one that is switched off.
 */
public final class Roles {

    private Roles() {}

    /**
     * Reads anything, changes nothing.
     *
     * <p>Includes the console, but only because the console has its own idea of what may run — a
     * viewer with a console could still write. So it does not: the console is denied to viewers and
     * the deny-list is about protecting the server, not the reader.
     */
    public static final String VIEWER = "viewer";

    /**
     * Everything a viewer can do, plus changing data: keys, values, publishing, and running
     * commands.
     *
     * <p>Cannot change connection profiles. Someone who may edit the data in a target is not
     * thereby someone who may point Keydra at a different target, or read the credentials it would
     * use to get there.
     */
    public static final String OPERATOR = "operator";

    /** Everything, including connection profiles, ACLs and the audit log. */
    public static final String ADMIN = "admin";

    /*
     * The hierarchy, written out.
     *
     * An admin is an operator and an operator is a viewer, but @RolesAllowed is an exact
     * list with no notion of one role containing another. Expressing it in a
     * SecurityIdentityAugmentor was tried and removed: augmentors do not run under
     * @TestSecurity, so the hierarchy would have held in production and not in the tests
     * that exist to prove it — a mechanism that works in one environment and not the other
     * is worse than none.
     *
     * So the acceptable roles are listed at each endpoint — @RolesAllowed needs a
     * compile-time constant array, which a named constant is not — and RoleMatrixTest
     * checks that a role can do everything the roles below it can.
     */
}
