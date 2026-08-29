package io.keydra.connections.exception;

import io.keydra.connections.entity.ConnectionProfile;

/**
 * An operation that could empty a guarded target arrived without naming it.
 *
 * <p>Its own type rather than a validation failure, because it is not a malformed request: the
 * caller asked for something that is allowed, on a target that asks to be named first. The message
 * says what to type, since a refusal that does not is a refusal somebody has to guess their way
 * past.
 */
public class TargetNotNamedException extends RuntimeException {

    public TargetNotNamedException(ConnectionProfile profile, String whatItWouldDo) {
        super(
                profile.name
                        + " is a target that has to be named before anything empties it. "
                        + whatItWouldDo
                        + " — send its name, exactly, to go ahead.");
    }
}
