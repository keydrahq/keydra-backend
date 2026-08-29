package io.keydra.authz.exception;

import java.time.Duration;

/**
 * Raised when a password is refused without being checked, because too many have just been tried.
 *
 * <p>Refusing before the check is the point rather than a shortcut. Argon2 is slow deliberately,
 * which makes each guess expensive for whoever is guessing and exactly as expensive for the server
 * — a few hundred concurrent attempts is a memory bill nobody authenticated to run up. Answering
 * early costs nothing and is the only version of this that does not trade one attack for another.
 *
 * <p>The message names a wait rather than a reason. Saying which limit was reached, or whether the
 * account exists, would answer for free the question the guessing is being done to answer.
 */
public class TooManyAttemptsException extends RuntimeException {

    private final Duration retryAfter;

    public TooManyAttemptsException(Duration retryAfter) {
        super(
                "Too many sign-in attempts. Wait "
                        + Math.max(1, retryAfter.toMinutes())
                        + " minutes and try again.");
        this.retryAfter = retryAfter;
    }

    public Duration retryAfter() {
        return retryAfter;
    }
}
