package io.keydra.authz.exception;

/**
 * An edit the permission model cannot accept.
 *
 * <p>Distinct from a refusal to act ({@link PermissionDeniedException}): the caller was allowed to
 * ask, and the answer is that what they asked for would leave the model in a shape it cannot answer
 * questions from — a group inside itself, a name already taken, a grant on the instance that also
 * names a scope.
 */
public class AuthzConflictException extends RuntimeException {

    public AuthzConflictException(String message) {
        super(message);
    }
}
