package io.keydra.cluster.persistence;

import io.keydra.cluster.entity.KeydraInstance;
import io.keydra.common.workload.Workload;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * The roster: who is running, as each of them last said.
 *
 * <p>Native SQL for the announcement, and for the same reason the lease uses it: {@code
 * last_seen_at} has to be the database's clock rather than the writer's, or two instances whose own
 * clocks differ would disagree about which of them was heard from more recently. There is no way to
 * say "now, as you reckon it" in HQL.
 */
@ApplicationScoped
public class InstanceRepository {

    /**
     * Says this instance is here, whether or not it has said so before, and comes back with the one
     * thing on the row this instance did not write.
     *
     * <p>The start time is written only on the first announcement — {@code ON CONFLICT} leaves it
     * alone — so an instance's uptime survives every beat that follows.
     *
     * <p>{@code draining} is left alone by the update for a different reason. It is an instruction
     * from outside, and an instance writing what it currently believes would undo it every beat.
     * The statement reads it back instead, so the beat that was already going to the database is
     * also how an instance learns it has been asked to stop.
     *
     * @param clearDraining true on the first announcement a process makes, and only there. A drain
     *     applies to a running process rather than to a name: an instance that was killed while
     *     draining leaves its row behind, and one that comes back under the same configured name
     *     would otherwise start up refusing to serve for a reason nobody typed.
     * @return whether this instance is now draining
     */
    @WithTransaction
    public Uni<Boolean> announce(
            String id,
            String version,
            String commit,
            long published,
            long received,
            long commands,
            Workload.Snapshot workload,
            boolean clearDraining) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createNativeQuery(announceSql(clearDraining), Boolean.class)
                                        .setParameter("id", id)
                                        .setParameter("version", version)
                                        .setParameter("commit", commit == null ? "" : commit)
                                        .setParameter("published", published)
                                        .setParameter("received", received)
                                        .setParameter("commands", commands)
                                        .setParameter("sockets", workload.sockets())
                                        .setParameter("streams", workload.streams())
                                        .setParameter("jobs", workload.jobs())
                                        .setParameter(
                                                "watching",
                                                TargetListConverter.join(workload.targets()))
                                        .getSingleResult())
                .map(draining -> draining != null && draining);
    }

    /**
     * The upsert, wrapped in a select.
     *
     * <p>{@code insert ... returning} would do, and the wrapping is what makes it unambiguously a
     * query rather than a statement: one round trip that both writes the beat and reads back the
     * instruction, without depending on how a driver chooses to report a row set from a write.
     */
    private static String announceSql(boolean clearDraining) {
        // Every column this writes is named, including the two it always writes false into. A
        // column left to the database's default is a column that works where Flyway made the table
        // and fails where the entities did — which is production and the test suite respectively,
        // and the wrong way round for finding out.
        //
        // absence_announced is written on the insert and never on the update. What somebody has
        // already said about this instance is not the instance's to take back on its next beat; it
        // is cleared by whoever notices it beating again, which is what turns the return into a
        // message rather than into silence.
        return """
        with announced as (
            insert into keydra_instance
                (id, version, commit, started_at, last_seen_at,
                 published, received, commands,
                 sockets, streams, jobs, watching, draining, absence_announced)
            values (:id, :version, :commit, now(), now(),
                    :published, :received, :commands,
                    :sockets, :streams, :jobs, :watching, false, false)
            on conflict (id) do update
               set last_seen_at = now(),
                   version = excluded.version,
                   commit = excluded.commit,
                   published = excluded.published,
                   received = excluded.received,
                   commands = excluded.commands,
                   sockets = excluded.sockets,
                   streams = excluded.streams,
                   jobs = excluded.jobs,
                   watching = excluded.watching
        """
                + (clearDraining ? "          , draining = false\n" : "")
                + """
                    returning draining
                )
                select draining from announced
                """;
    }

    /**
     * Asks an instance to stop taking new work, or to start again.
     *
     * <p>Written by whichever instance received the request, which is almost never the one it is
     * about: two Keydras do not connect to each other, so this row is the only way the instruction
     * reaches its subject. It acts on it on its next beat.
     *
     * @return false where there is no such instance, which is a 404 rather than a silent success
     */
    @WithTransaction
    public Uni<Boolean> setDraining(String id, boolean draining) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "update KeydraInstance set draining = :draining"
                                                        + " where id = :id")
                                        .setParameter("draining", draining)
                                        .setParameter("id", id)
                                        .executeUpdate())
                .map(rows -> rows > 0);
    }

    /**
     * Everybody heard from recently, newest first.
     *
     * <p>Recently rather than ever, because a row nobody removed is not a running instance. The
     * window is the caller's: what counts as gone is a property of how often the beat happens.
     *
     * <p>Zero is allowed and means "nothing counts", which is a real answer rather than a mistake
     * to clamp away — and is what makes the cutoff testable without waiting for it.
     */
    @WithSession
    public Uni<List<KeydraInstance>> seenWithin(int seconds) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from KeydraInstance where lastSeenAt > :cutoff"
                                                        + " order by startedAt desc",
                                                KeydraInstance.class)
                                        .setParameter(
                                                "cutoff",
                                                java.time.Instant.now()
                                                        .minusSeconds(Math.max(0, seconds)))
                                        .getResultList());
    }

    /**
     * Forgets instances nobody has heard from in a long while.
     *
     * <p>Long after they stop being listed, so the sweep is tidying rather than deciding. An
     * instance that comes back announces itself again and gets a new start time, which is the
     * truth: it did start again.
     */
    @WithTransaction
    public Uni<Integer> forgetOlderThan(int seconds) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "delete from KeydraInstance where lastSeenAt <"
                                                        + " :cutoff")
                                        .setParameter(
                                                "cutoff",
                                                java.time.Instant.now()
                                                        .minusSeconds(Math.max(0, seconds)))
                                        .executeUpdate());
    }

    /**
     * Removes this instance on the way out, so a clean stop is not a gap somebody has to wait for.
     */
    @WithTransaction
    public Uni<Integer> forget(String id) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery("delete from KeydraInstance where id = :id")
                                        .setParameter("id", id)
                                        .executeUpdate());
    }

    /**
     * Instances that have gone quiet and that nobody has said so about yet.
     *
     * <p>Three things at once, and each of them is a decision. Older than the window that separates
     * absence from a slow beat; not draining, because somebody took that one out of service on
     * purpose and stopping it afterwards is a departure with a step in front of it; and not already
     * announced, because this runs on every instance's timer.
     *
     * <p>An instance that left cleanly is not here at all — {@code leave()} removed its row — which
     * is the whole distinction this phase turns on: a row that vanishes is a departure and a row
     * that ages is a death.
     */
    @WithSession
    public Uni<List<KeydraInstance>> absentUnannounced(int seconds) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from KeydraInstance where absenceAnnounced = false"
                                                        + " and draining = false and lastSeenAt <"
                                                        + " :cutoff order by lastSeenAt",
                                                KeydraInstance.class)
                                        .setParameter(
                                                "cutoff",
                                                java.time.Instant.now()
                                                        .minusSeconds(Math.max(1, seconds)))
                                        .getResultList());
    }

    /** Instances that were announced as gone and are beating again, which needs a configured id. */
    @WithSession
    public Uni<List<KeydraInstance>> returnedAfterAbsence(int seconds) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from KeydraInstance where absenceAnnounced = true"
                                                        + " and lastSeenAt >= :cutoff order by"
                                                        + " lastSeenAt",
                                                KeydraInstance.class)
                                        .setParameter(
                                                "cutoff",
                                                java.time.Instant.now()
                                                        .minusSeconds(Math.max(1, seconds)))
                                        .getResultList());
    }

    /**
     * Takes the right to speak about one instance's silence, or does not.
     *
     * <p>The update names the value it expects to find, so exactly one instance wins it and the
     * others change no row. That is what lets this run without anybody being in charge — which it
     * has to, because what it is watching for includes nobody being in charge.
     *
     * @param announced what to move it to: true when the silence is being announced, false when the
     *     instance has started beating again and the return is
     */
    @WithTransaction
    public Uni<Boolean> claimAbsence(String id, boolean announced) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "update KeydraInstance set absenceAnnounced ="
                                                        + " :announced where id = :id and"
                                                        + " absenceAnnounced = :was")
                                        .setParameter("announced", announced)
                                        .setParameter("was", !announced)
                                        .setParameter("id", id)
                                        .executeUpdate())
                .map(changed -> changed > 0);
    }
}
