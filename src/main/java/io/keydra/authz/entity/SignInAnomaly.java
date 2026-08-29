package io.keydra.authz.entity;

/**
 * Something about a sign-in worth telling somebody about.
 *
 * <p>None of these is a refusal. A sign-in that carries every one of them still succeeded — the
 * password was right — and the point of naming them is that a right password is exactly what an
 * attacker has. What these describe is the shape around it: where it came from, what came before
 * it, and whether that matches what this account has done until now.
 *
 * <p>They are deliberately few. A list of thirty signals is a list nobody reads, and a signal that
 * fires on an ordinary Tuesday teaches people to dismiss the ones that matter.
 */
public enum SignInAnomaly {

    /** From a network this account has never signed in from before. */
    NEW_NETWORK,

    /** From a country this account has never signed in from before. */
    NEW_COUNTRY,

    /**
     * Two sign-ins from different countries, closer together than anybody could travel between
     * them. One of the two was not this person.
     */
    IMPOSSIBLE_TRAVEL,

    /** A browser this account has not been seen using. */
    NEW_DEVICE,

    /** Enough wrong passwords immediately before this one to suggest the right one was guessed. */
    AFTER_REPEATED_FAILURES,

    /** More sign-ins from this source, in this window, than a person makes. */
    UNUSUAL_VOLUME,

    /**
     * This source has signed into several different accounts, which is what stuffing looks like.
     */
    MANY_ACCOUNTS_ONE_SOURCE,

    /** The account had not been used for a long time and now has been. */
    DORMANT_ACCOUNT
}
