package io.keydra.alerts.entity;

/**
 * What a rule's threshold is measured against.
 *
 * <p>Two ways of saying the same kind of thing, and the difference is whose knowledge the number
 * carries. An absolute rule is somebody's estimate of what this server ought to look like; a
 * baseline rule is the server's own record of what it did look like, which stays right through a
 * capacity change nobody remembered to re-tune for.
 */
public enum AlertBasis {

    /** The threshold is the reading itself, in the metric's own unit. */
    ABSOLUTE,

    /**
     * The threshold is a percentage of what the metric read over an earlier window.
     *
     * <p>A hundred and forty is forty per cent above it; sixty is forty per cent below. A
     * percentage rather than a difference because "twice as busy" means the same thing on a server
     * doing ten requests a second and one doing ten thousand, and a difference does not.
     */
    BASELINE
}
