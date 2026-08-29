package io.keydra.cluster.service;

import jakarta.enterprise.context.ApplicationScoped;
import org.jboss.logging.Logger;

/**
 * Whether this instance has been asked to stop taking new work.
 *
 * <p>One boolean, held where everything that has to act on it can read it without going to the
 * database. Readiness is probed more often than the beat, the lease is decided on every beat, and
 * the migration sweep asks each time it runs — three questions per few seconds, all of them
 * answered by the last beat, which was already going to the database anyway.
 *
 * <p>A separate bean rather than a method on {@link InstanceRegistry} because of who reads it: the
 * health check and the migration sweep both need the answer and neither has any business with the
 * roster. This is the fact; the registry is where it comes from.
 *
 * <p>Never cleared by a failure. An instance that cannot reach the database has not been told to
 * carry on — it has been told nothing — and the belief it already holds is the better of the two
 * answers available.
 */
@ApplicationScoped
public class Draining {

    private static final Logger LOG = Logger.getLogger(Draining.class);

    private volatile boolean underWay;

    /**
     * Whether this instance is draining, as of its last beat.
     *
     * <p>A few seconds old at worst, which is the same freshness as everything else that comes off
     * the beat. Nothing here needs it sooner: a drain is followed by somebody stopping the
     * instance, and the gap between the two is measured in whatever it takes a person to look at
     * the page.
     */
    public boolean underWay() {
        return underWay;
    }

    /**
     * What the beat read back.
     *
     * <p>Package-private: the only thing entitled to say this is the announcement, because the
     * database row is where the instruction actually lives.
     */
    void observed(boolean now) {
        if (now == underWay) {
            return;
        }
        underWay = now;
        if (now) {
            LOG.infof(
                    "Instance %s is draining: it will report itself unready, give up the chores and"
                            + " take no new long work",
                    InstanceId.get());
        } else {
            LOG.infof("Instance %s is no longer draining", InstanceId.get());
        }
    }
}
