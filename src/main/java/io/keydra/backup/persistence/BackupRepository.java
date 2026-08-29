package io.keydra.backup.persistence;

import io.keydra.backup.entity.BackupDestination;
import io.keydra.backup.entity.BackupRecipient;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Reads and writes the places backups are sent. */
@ApplicationScoped
public class BackupRepository {

    public Uni<List<BackupDestination>> all() {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from BackupDestination order by name",
                                                BackupDestination.class)
                                        .getResultList())
                .flatMap(this::withRecipients);
    }

    public Uni<BackupDestination> byId(Long id) {
        return Panache.getSession()
                .flatMap(session -> session.find(BackupDestination.class, id))
                .flatMap(this::withRecipients);
    }

    /**
     * One destination, read in a session of its own.
     *
     * <p>Its own because sending a backup must not happen inside one: an upload takes as long as
     * the file and the network make it, and a session held across it is a database connection held
     * across it — the same rule the scheduled runs follow.
     */
    @WithSession
    public Uni<BackupDestination> forUse(Long id) {
        return byId(id);
    }

    public Uni<BackupDestination> byName(String name) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from BackupDestination where name = :name",
                                                BackupDestination.class)
                                        .setParameter("name", name)
                                        .getSingleResultOrNull())
                .flatMap(this::withRecipients);
    }

    public Uni<BackupDestination> save(BackupDestination destination) {
        return Panache.getSession()
                .flatMap(session -> session.persist(destination).replaceWith(destination));
    }

    public Uni<Boolean> delete(Long id) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery("delete from BackupDestination where id = :id")
                                        .setParameter("id", id)
                                        .executeUpdate())
                .map(deleted -> deleted > 0);
    }

    // --- The keys a destination's backups can be opened with ----------------

    /**
     * Fills in the recipients a destination was loaded without.
     *
     * <p>Here rather than as a mapped collection: a collection on the entity would be a lazy load
     * on a reactive session, and every caller of this repository would have to know not to touch it
     * outside one. Filling it in on the way out means the entity is complete wherever it arrives.
     */
    private Uni<BackupDestination> withRecipients(BackupDestination destination) {
        if (destination == null) {
            return Uni.createFrom().nullItem();
        }
        return recipientsFor(destination.id)
                .invoke(found -> destination.recipients = found)
                .replaceWith(destination);
    }

    /** The same for a whole page of them, in one query rather than one per row. */
    private Uni<List<BackupDestination>> withRecipients(List<BackupDestination> destinations) {
        if (destinations.isEmpty()) {
            return Uni.createFrom().item(destinations);
        }
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from BackupRecipient where destinationId in"
                                                        + " :ids order by addedAt, id",
                                                BackupRecipient.class)
                                        .setParameter(
                                                "ids",
                                                destinations.stream()
                                                        .map(destination -> destination.id)
                                                        .toList())
                                        .getResultList())
                .map(
                        rows -> {
                            Map<Long, List<BackupRecipient>> byDestination =
                                    rows.stream()
                                            .collect(
                                                    Collectors.groupingBy(
                                                            row -> row.destinationId));
                            for (BackupDestination destination : destinations) {
                                destination.recipients =
                                        byDestination.getOrDefault(destination.id, List.of());
                            }
                            return destinations;
                        });
    }

    public Uni<List<BackupRecipient>> recipientsFor(Long destinationId) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from BackupRecipient where destinationId = :id"
                                                        + " order by addedAt, id",
                                                BackupRecipient.class)
                                        .setParameter("id", destinationId)
                                        .getResultList());
    }

    /**
     * Writes the list a destination was saved with, replacing whatever was there.
     *
     * <p>Wholesale, because that is how it arrives: the form sends the list it wants and there is
     * no endpoint that adds or removes one. Keeping the rows that survived would mean matching them
     * by key, which buys nothing a person can see and costs a rule about what happens when two
     * labels point at the same key.
     */
    public Uni<Void> replaceRecipients(Long destinationId, List<BackupRecipient> recipients) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "delete from BackupRecipient where destinationId ="
                                                        + " :id")
                                        .setParameter("id", destinationId)
                                        .executeUpdate()
                                        .flatMap(
                                                ignored -> {
                                                    Uni<Void> chain = Uni.createFrom().voidItem();
                                                    for (BackupRecipient recipient : recipients) {
                                                        recipient.id = null;
                                                        recipient.destinationId = destinationId;
                                                        chain =
                                                                chain.flatMap(
                                                                        done ->
                                                                                session.persist(
                                                                                        recipient));
                                                    }
                                                    return chain;
                                                }));
    }
}
