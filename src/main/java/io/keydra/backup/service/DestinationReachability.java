package io.keydra.backup.service;

import io.keydra.backup.persistence.BackupRepository;
import io.keydra.common.reach.Reachable;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Whether the backup destinations answer.
 *
 * <p>The check is the round trip {@link DestinationService#check(Long)} already makes — write a
 * small file, look for it, take it away again — because credentials that can log in and not write
 * are the commonest way a destination is wrong, and a check that only opened a connection would
 * pass for one that cannot be written to. That javadoc already says the moment to find that out is
 * not three in the morning three weeks later; this is what makes it true without anybody pressing
 * anything.
 */
@ApplicationScoped
public class DestinationReachability implements Reachable {

    /** Stable: half of the key every answer is stored under. */
    public static final String KIND = "backup-destination";

    private final BackupRepository repository;
    private final DestinationService destinations;

    @Inject
    DestinationReachability(BackupRepository repository, DestinationService destinations) {
        this.repository = repository;
        this.destinations = destinations;
    }

    @Override
    public String kind() {
        return KIND;
    }

    @Override
    public String describedAs() {
        return "backup destination";
    }

    @Override
    @WithSession
    public Uni<List<Subject>> subjects() {
        return repository
                .all()
                .map(
                        found ->
                                found.stream()
                                        .map(
                                                destination ->
                                                        new Subject(
                                                                destination.id,
                                                                destination.name,
                                                                destination.enabled))
                                        .toList());
    }

    @Override
    public Uni<Outcome> check(Long id) {
        return destinations
                .check(id)
                .map(answer -> answer.reachable() ? Outcome.fine() : Outcome.not(answer.message()))
                .onFailure()
                .recoverWithItem(failure -> Outcome.not(plainest(failure)));
    }

    private static String plainest(Throwable failure) {
        Throwable deepest = failure;
        while (deepest.getCause() != null && deepest.getCause() != deepest) {
            deepest = deepest.getCause();
        }
        String message = deepest.getMessage();
        return message == null || message.isBlank() ? deepest.getClass().getSimpleName() : message;
    }
}
