package io.keydra.console.service;

import io.keydra.console.dto.HistoryEntry;
import io.keydra.console.entity.CommandHistoryEntry;
import io.keydra.console.persistence.CommandHistoryRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Remembers what was typed.
 *
 * <p>A bean of its own rather than methods on {@link ConsoleService}, for a reason the type system
 * does not show: {@code @WithSession} and {@code @WithTransaction} are interceptors, and an
 * interceptor does not run when a bean calls its own method. Recording from inside the service
 * silently ran without a session — which fails outright on the WebSocket path, where no request has
 * opened one.
 */
@ApplicationScoped
public class ConsoleHistoryService {

    private final CommandHistoryRepository history;
    private final int limit;

    @Inject
    ConsoleHistoryService(
            CommandHistoryRepository history,
            @ConfigProperty(name = "keydra.console.history-limit", defaultValue = "200")
                    int limit) {
        this.history = history;
        this.limit = limit;
    }

    /** Records one line and drops anything past the limit. */
    @WithTransaction
    public Uni<Void> record(Long connectionId, Long userId, String line, Instant at) {
        return history.persist(CommandHistoryEntry.of(connectionId, userId, line, at))
                .chain(() -> history.trim(connectionId, userId, limit))
                .replaceWithVoid();
    }

    @WithSession
    public Uni<List<HistoryEntry>> recent(Long connectionId, Long userId) {
        return history.recentFor(connectionId, userId, limit)
                .map(
                        entries ->
                                entries.stream()
                                        .map(
                                                entry ->
                                                        new HistoryEntry(
                                                                entry.id,
                                                                entry.line,
                                                                entry.executedAt))
                                        .toList());
    }

    /**
     * Forgets this person's history on this target, and nobody else's.
     *
     * <p>It used to forget everybody's. Clearing was reachable by anybody with CONSOLE_RUN, so the
     * record of what an administrator had typed against a server could be removed by whoever came
     * along next — the audit entry said a history was cleared and not what had been in it.
     */
    @WithTransaction
    public Uni<Long> clear(Long connectionId, Long userId) {
        return history.deleteFor(connectionId, userId);
    }
}
