package io.keydra.authz.entity;

/**
 * How one attempt to sign in ended.
 *
 * <p>{@link #WRONG_PASSWORD} and {@link #NO_SUCH_ACCOUNT} are separate here and are one thing to
 * the person at the form, who is told neither. The distinction is for whoever reads the history
 * afterwards: a run of wrong passwords against one real account is somebody guessing a password,
 * and a run of usernames that do not exist is somebody working through a list they got elsewhere.
 * Telling those apart is most of what makes the history worth keeping.
 */
public enum SignInOutcome {

    /** Signed in. */
    SUCCEEDED,

    /** The account exists and the password was wrong. */
    WRONG_PASSWORD,

    /** No account by that name. */
    NO_SUCH_ACCOUNT,

    /** Refused without the password being checked, because too many had just been tried. */
    REFUSED_TOO_MANY,

    /**
     * The password was right and the second factor was not.
     *
     * <p>Its own outcome rather than another wrong password, because the two mean opposite things
     * to whoever reads the history: a run of wrong passwords is somebody guessing, and a run of
     * these is somebody who has the password already.
     */
    WRONG_SECOND_FACTOR;

    public boolean failed() {
        return this != SUCCEEDED;
    }
}
