package io.keydra.schedule;

import io.keydra.schedule.persistence.ScheduleRepository;
import io.keydra.schedule.service.JobScheduler;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;

/**
 * Leaves the scheduler and its tables empty.
 *
 * <p>Both halves matter. Deleting the rows without taking the schedules off the clock leaves the
 * scheduler firing jobs whose rows are gone, every minute, for the rest of the suite — which is a
 * page of warnings in every log after it and a run that starts while the next test is setting up.
 */
public final class ScheduleFixtures {

    private ScheduleFixtures() {}

    public static void deleteEverySchedule() {
        ScheduleRepository repository = Arc.container().instance(ScheduleRepository.class).get();
        JobScheduler scheduler = Arc.container().instance(JobScheduler.class).get();
        try {
            VertxContextSupport.subscribeAndAwait(
                    () ->
                            Panache.withTransaction(
                                    () ->
                                            repository
                                                    .all()
                                                    .flatMap(
                                                            jobs -> {
                                                                jobs.forEach(
                                                                        job ->
                                                                                scheduler
                                                                                        .unregister(
                                                                                                job.id));
                                                                return clear();
                                                            })));
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not clear the schedules", failure);
        }
    }

    private static Uni<Integer> clear() {
        // Runs before schedules: a run points at one, and the test schema has no cascade.
        return Panache.getSession()
                .flatMap(session -> session.createQuery("delete from JobRun").executeUpdate())
                .flatMap(
                        ignored ->
                                Panache.getSession()
                                        .flatMap(
                                                session ->
                                                        session.createQuery(
                                                                        "delete from ScheduledJob")
                                                                .executeUpdate()));
    }
}
