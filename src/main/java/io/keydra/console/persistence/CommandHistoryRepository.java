package io.keydra.console.persistence;

import io.keydra.console.entity.CommandHistoryEntry;
import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/** Persistence for console history. */
@ApplicationScoped
public class CommandHistoryRepository implements PanacheRepository<CommandHistoryEntry> {

    /**
     * Most recent first, which is the order an up-arrow walks.
     *
     * <p>Scoped to the person as well as the target, everywhere. A history is somebody's own
     * typing, and the owner is part of every query here rather than a filter applied above — a
     * lookup that forgot it would quietly hand back everybody's.
     */
    public Uni<List<CommandHistoryEntry>> recentFor(Long connectionId, Long userId, int limit) {
        return find(
                        mine(userId),
                        Sort.by("id", Sort.Direction.Descending),
                        owner(connectionId, userId))
                .page(0, limit)
                .list();
    }

    public Uni<Long> deleteFor(Long connectionId, Long userId) {
        return delete(mine(userId), owner(connectionId, userId));
    }

    /**
     * "This person's lines on this target", where the person may be nobody.
     *
     * <p>An instance with enforcement off has no accounts, so every line is written with no owner
     * and every reader is the same nobody. {@code userId = :userId} never matches null in SQL, so
     * asking that way would give an unsecured instance a console whose history was always empty.
     * The two cases are written out rather than left to a comparison that quietly answers no.
     */
    private static String mine(Long userId) {
        return userId == null
                ? "connectionId = :connectionId and userId is null"
                : "connectionId = :connectionId and userId = :userId";
    }

    private static Parameters owner(Long connectionId, Long userId) {
        Parameters parameters = Parameters.with("connectionId", connectionId);
        return userId == null ? parameters : parameters.and("userId", userId);
    }

    /**
     * Drops everything older than the newest {@code keep} entries for one target.
     *
     * <p>A console left open for a week would otherwise grow a table nobody reads the bottom of.
     * The limit is per person per target, which is what it always meant to be.
     */
    public Uni<Long> trim(Long connectionId, Long userId, int keep) {
        return find(
                        mine(userId),
                        Sort.by("id", Sort.Direction.Descending),
                        owner(connectionId, userId))
                .page(keep, 1)
                .firstResult()
                .flatMap(
                        oldest ->
                                oldest == null
                                        ? Uni.createFrom().item(0L)
                                        : delete(
                                                mine(userId) + " and id <= :id",
                                                owner(connectionId, userId).and("id", oldest.id)));
    }
}
