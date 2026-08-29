package io.keydra.cluster;

import io.keydra.cluster.service.Draining;
import io.keydra.cluster.service.InstanceId;
import io.keydra.cluster.service.Leadership;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.vertx.VertxContextSupport;
import java.time.Duration;
import org.awaitility.Awaitility;

/**
 * Hands the chores to an instance that does not exist, and takes them back.
 *
 * <p>The only way to test "more than one Keydra" inside one process. What a second instance does to
 * the first is written entirely in one row, so a test can write that row — and then watch this
 * instance notice, which is the part that matters.
 *
 * <p>The write here is deliberately a forced one, which nothing in the application does: taking a
 * live lease from its holder is precisely what the real statement refuses.
 */
public final class ClusterFixtures {

    /** The instance that is not there. Nothing renews this lease; the test takes it away again. */
    public static final String SOMEBODY_ELSE = "another-instance";

    private ClusterFixtures() {}

    /** Leaves this instance a follower, and waits until it knows it. */
    public static void giveTheChoresAway() {
        run(
                "insert into instance_lease (role, holder, expires_at) values ('"
                        + Leadership.CHORES
                        + "', '"
                        + SOMEBODY_ELSE
                        + "', now() + interval '120 seconds') on conflict (role) do update set"
                        + " holder = excluded.holder, expires_at = excluded.expires_at");
        awaitLeadership(false);
    }

    /** Gives them back, and waits until this instance has taken them on again. */
    public static void takeTheChoresBack() {
        run("delete from instance_lease where role = '" + Leadership.CHORES + "'");
        awaitLeadership(true);
    }

    /**
     * Asks this instance to stop taking new work, and waits until it has noticed.
     *
     * <p>Written straight into the row, which is exactly what the endpoint does — the instruction
     * lives in the database because there is no instance to send it to. What a test then waits for
     * is the beat picking it up, which is the part with any behaviour in it.
     */
    public static void drainThisInstance() {
        setDraining(true);
    }

    /** And puts it back, so a test that drained this instance does not leave it drained. */
    public static void resumeThisInstance() {
        setDraining(false);
    }

    private static void setDraining(boolean draining) {
        run(
                "update keydra_instance set draining = "
                        + draining
                        + " where id = '"
                        + InstanceId.get()
                        + "'");
        Draining state = Arc.container().instance(Draining.class).get();
        Awaitility.await().atMost(Duration.ofSeconds(20)).until(() -> state.underWay() == draining);
    }

    private static void awaitLeadership(boolean expected) {
        Leadership leadership = Arc.container().instance(Leadership.class).get();
        Awaitility.await()
                .atMost(Duration.ofSeconds(20))
                .until(() -> leadership.isLeader() == expected);
    }

    private static void run(String sql) {
        try {
            VertxContextSupport.subscribeAndAwait(
                    () ->
                            Panache.withTransaction(
                                    () ->
                                            Panache.getSession()
                                                    .flatMap(
                                                            session ->
                                                                    session.createNativeQuery(sql)
                                                                            .executeUpdate())));
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not move the lease", failure);
        }
    }

    /**
     * Writes a row for an instance that is not there, last heard from a while ago.
     *
     * <p>The same trick the lease fixture uses and for the same reason: what a second Keydra is, as
     * far as this process is concerned, is a row. One that stopped without shutting down is a row
     * that stopped moving — which is precisely what this writes.
     */
    public static void pretendInstance(String id, int secondsAgo, boolean draining) {
        run(
                "insert into keydra_instance (id, version, commit, started_at, last_seen_at,"
                    + " published, received, commands, sockets, streams, jobs, watching, draining,"
                    + " absence_announced) values ('"
                        + id
                        + "', 'test', '', now() - interval '"
                        + secondsAgo
                        + " seconds', now() - interval '"
                        + secondsAgo
                        + " seconds', 0, 0, 0, 0, 0, 0, '', "
                        + draining
                        + ", false) on conflict (id) do update set last_seen_at ="
                        + " excluded.last_seen_at, draining = excluded.draining,"
                        + " absence_announced = false");
    }

    /** Whether somebody has already said this instance stopped answering. */
    public static boolean absenceAnnounced(String id) {
        return Boolean.TRUE.equals(
                read(
                        "select absence_announced from keydra_instance where id = '" + id + "'",
                        Boolean.class));
    }

    /** Removes a pretend instance, so a class that wrote one does not leave it on the roster. */
    public static void forgetInstance(String id) {
        run("delete from keydra_instance where id = '" + id + "'");
    }

    /**
     * Leaves the chores lapsed rather than held, which needs this instance to be draining too.
     *
     * <p>An expired lease is claimable, and a running instance claims one on its next beat — so
     * "nobody is doing the chores" cannot be arranged by expiring a row alone. A drained fleet is
     * how it happens for real, and it is how it is arranged here.
     */
    public static void letTheChoresLapse(int secondsAgo) {
        run(
                "insert into instance_lease (role, holder, expires_at) values ('"
                        + Leadership.CHORES
                        + "', '"
                        + SOMEBODY_ELSE
                        + "', now() - interval '"
                        + secondsAgo
                        + " seconds') on conflict (role) do update set holder = excluded.holder,"
                        + " expires_at = excluded.expires_at");
    }

    /** Whether anybody has said the chores stopped. */
    public static boolean choresAnnounced() {
        return Boolean.TRUE.equals(
                read(
                        "select firing from instance_notice_state where subject = 'chores'",
                        Boolean.class));
    }

    /** Forgets what has been said, so each test starts from nothing having been said. */
    public static void clearWhatWasSaid() {
        run("delete from instance_notice_state");
    }

    private static <T> T read(String sql, Class<T> type) {
        try {
            return VertxContextSupport.subscribeAndAwait(
                    () ->
                            Panache.withSession(
                                    () ->
                                            Panache.getSession()
                                                    .flatMap(
                                                            session ->
                                                                    session.createNativeQuery(
                                                                                    sql, type)
                                                                            .getSingleResultOrNull())));
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not read the fleet's state", failure);
        }
    }
}
