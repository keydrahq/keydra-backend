package io.keydra.engine;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A user the store knows about.
 *
 * <p>Carries no password and no hash. The store will give out its password hashes, and putting them
 * on the wire and into a browser would be handing out something that is only useful for attacking
 * the target — the UI has no use for them, so they never leave the server.
 *
 * @param username the name it authenticates as
 * @param enabled whether the store will accept a login for it
 * @param rules the store's own rule strings, as it reports them
 * @param keyPatterns key patterns it may touch
 * @param channelPatterns pub/sub channel patterns it may touch
 * @param commands the command rules, as the store phrases them
 * @param hasPassword whether any password is set, which is all the UI needs to know
 */
@Schema(name = "AclUser", description = "A user the target knows about")
public record AclUser(
        String username,
        boolean enabled,
        List<String> rules,
        List<String> keyPatterns,
        List<String> channelPatterns,
        String commands,
        boolean hasPassword) {}
