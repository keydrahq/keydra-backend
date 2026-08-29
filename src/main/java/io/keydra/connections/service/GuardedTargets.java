package io.keydra.connections.service;

import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.exception.TargetNotNamedException;

/**
 * The check a guarded target makes before anything can empty it.
 *
 * <p>Static and tiny on purpose. What it does is compare two strings; what it is worth is that the
 * comparison happens on the server. Until phase 59 the only confirmation in the product was a
 * dialog in the browser, which is where a confirmation is shown and not where one can be enforced —
 * anything holding {@code keys:delete} could purge a keyspace with one request and be asked
 * nothing.
 *
 * <p>The word is the target's own name rather than a word like DELETE. Typing DELETE proves that
 * somebody can read; typing {@code orders-cache} while looking at a page that says {@code
 * orders-prod} tells them, by their own hands, which server they are on — and having the wrong
 * server is the mistake this exists to catch.
 */
public final class GuardedTargets {

    private GuardedTargets() {}

    /**
     * Refuses unless the caller named the target, where the target asks to be named.
     *
     * <p>Exactly, and case sensitively. A comparison that trimmed would accept a trailing space
     * from a paste, and one that ignored case would accept a name from somebody who was not reading
     * — which is the whole population this is for.
     *
     * @param profile the target the operation would reach
     * @param named what the caller says it is called, or null where they said nothing
     * @param whatItWouldDo one clause describing the operation, for the refusal to quote
     */
    public static void requireNamed(ConnectionProfile profile, String named, String whatItWouldDo) {
        if (profile == null || !profile.guarded) {
            return;
        }
        if (profile.name != null && profile.name.equals(named)) {
            return;
        }
        throw new TargetNotNamedException(profile, whatItWouldDo);
    }
}
