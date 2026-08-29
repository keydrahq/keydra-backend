package io.keydra.common.workload;

import java.util.Set;

/**
 * What one domain is currently holding on this instance.
 *
 * <p>Implemented by every domain that keeps live work in memory — open sockets, subscriptions
 * against a target, a migration walking a keyspace — and collected by the cluster domain on the
 * beat that renews the lease. The direction matters: a class in {@code cluster} that reached into
 * five domains to count their maps would know the internals of all five and would need editing
 * every time a sixth started holding something. This way the new domain implements an interface it
 * already depends on and the roster picks it up.
 *
 * <p>It lives in {@code common} for the reason anything does: everything depends on {@code common}
 * and {@code common} depends on nothing, so this is the only place five domains can meet without
 * any of them importing another.
 *
 * <p><strong>{@link #snapshot()} must not block.</strong> It runs on the heartbeat, the heartbeat
 * is what keeps the lease, and a count that went to a database or a socket to answer would be
 * trading the lease for a number.
 */
public interface Workload {

    /** What this domain holds right now, read from memory. */
    Snapshot snapshot();

    /**
     * One domain's answer, in the four terms the roster keeps.
     *
     * <p>A domain fills in the one or two it has and leaves the rest empty; the registry adds them
     * up. Deliberately not a load figure — heap, CPU and pool depth are Micrometer's, they are
     * already on the metrics endpoint, and a second answer to a question that has one is how two
     * dashboards start disagreeing.
     *
     * @param sockets browsers this instance is talking to
     * @param streams connections held open against a target because somebody is looking at them
     * @param jobs long work under way — a keyspace being walked, a tunnel being held
     * @param targets which servers this instance holds clients for, by profile id. Ids rather than
     *     a count, because the count is what a count of anything is and the question is which
     */
    record Snapshot(int sockets, int streams, int jobs, Set<Long> targets) {

        public static final Snapshot NONE = new Snapshot(0, 0, 0, Set.of());

        public static Snapshot ofSockets(int sockets) {
            return new Snapshot(sockets, 0, 0, Set.of());
        }

        public static Snapshot ofStreams(int streams) {
            return new Snapshot(0, streams, 0, Set.of());
        }

        public static Snapshot ofJobs(int jobs) {
            return new Snapshot(0, 0, jobs, Set.of());
        }

        public static Snapshot ofTargets(Set<Long> targets) {
            return new Snapshot(0, 0, 0, Set.copyOf(targets));
        }
    }
}
