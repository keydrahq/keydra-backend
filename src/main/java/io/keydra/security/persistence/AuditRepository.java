package io.keydra.security.persistence;

import io.keydra.common.graphql.Cursors;
import io.keydra.security.dto.AuditQuery;
import io.keydra.security.entity.AuditEvent;
import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.quarkus.panache.common.Parameters;
import io.quarkus.panache.common.Sort;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Persistence for the audit log. */
@ApplicationScoped
public class AuditRepository implements PanacheRepository<AuditEvent> {

    /**
     * Recent events, newest first, narrowed by whichever filters were given.
     *
     * <p>Built as one query with optional clauses rather than a method per combination: the filters
     * are independent and a caller may use any of them.
     */
    public Uni<List<AuditEvent>> search(
            String actor, String action, Long connectionId, Instant since, int limit) {
        StringBuilder where = new StringBuilder("1 = 1");
        Parameters parameters = new Parameters();

        if (actor != null && !actor.isBlank()) {
            where.append(" and actor = :actor");
            parameters.and("actor", actor);
        }
        if (action != null && !action.isBlank()) {
            where.append(" and action = :action");
            parameters.and("action", action);
        }
        if (connectionId != null) {
            where.append(" and connectionId = :connectionId");
            parameters.and("connectionId", connectionId);
        }
        if (since != null) {
            where.append(" and at >= :since");
            parameters.and("since", since);
        }

        return find(where.toString(), Sort.by("id", Sort.Direction.Descending), parameters)
                .page(0, limit)
                .list();
    }

    /**
     * One page of events, and how many matched in all.
     *
     * <p>Resumed from a cursor rather than counted to from the start. The audit log takes a row on
     * every action anybody performs, so between reading one page and asking for the next there are
     * usually new rows at the front — and with an offset those rows push the whole list down, so
     * the second page begins with something already on the first. "After this id" is not affected
     * by what arrives in front of it.
     *
     * <p>The cursor narrows the rows and never the count: how many there are is a question about
     * the list, not about where somebody has read up to.
     *
     * <p>One more row is asked for than wanted and never returned. Whether it came back is the
     * whole answer to "is there a next page", and the alternative is another query to learn one
     * bit.
     */
    public Uni<AuditRows> page(AuditQuery query, Cursors.Position after, int size) {
        Narrowed narrowed = narrow(query);
        Narrowed sliced = after == null ? narrowed : narrowed.resumingAfter(after);
        return find(sliced.where(), Sort.by("id", Sort.Direction.Descending), sliced.bindings())
                .page(0, size + 1)
                .list()
                .flatMap(
                        rows ->
                                // After the rows rather than beside them: one session serves one
                                // statement at a time.
                                find(
                                                narrowed.where(),
                                                Sort.by("id", Sort.Direction.Descending),
                                                narrowed.bindings())
                                        .count()
                                        .map(total -> trimmed(rows, size, total)));
    }

    private static AuditRows trimmed(List<AuditEvent> rows, int size, long total) {
        boolean more = rows.size() > size;
        return new AuditRows(more ? rows.subList(0, size) : rows, total, more);
    }

    /**
     * A set of filters, held as a map rather than as Panache's {@code Parameters}.
     *
     * <p>The map is copied on every derivation and turned into {@code Parameters} only at the
     * moment a query is built, because {@code Parameters.and} changes the object it is called on
     * and hands it back. Adding the cursor to the page's parameters therefore also added it to the
     * count's — the same object — and the count's statement has no cursor in it. What came back was
     * Hibernate saying there is no parameter named cursorId in a query with no named parameters at
     * all, which names neither the cursor nor the count nor the sharing between them.
     *
     * @param where the clause these bind to
     * @param values what to bind, by name
     */
    private record Narrowed(String where, Map<String, Object> values) {

        /**
         * The same filters, plus "and before the row this cursor names".
         *
         * <p>Before rather than after, because the log is newest first and its ids ascend: the row
         * after the one you are looking at, in reading order, is the one with the smaller id. The
         * log is ordered by id alone, so there is no second half to the comparison — an id is
         * unique and nothing ties with it.
         */
        Narrowed resumingAfter(Cursors.Position after) {
            Map<String, Object> also = new LinkedHashMap<>(values);
            also.put("cursorId", Long.parseLong(after.id()));
            return new Narrowed(where + " and id < :cursorId", also);
        }

        Parameters bindings() {
            Parameters parameters = new Parameters();
            values.forEach(parameters::and);
            return parameters;
        }
    }

    /**
     * The filters, turned into one clause.
     *
     * <p>Shared by the page and its count so the two cannot be narrowed differently. Optional
     * clauses rather than a method per combination: the filters are independent and a caller may
     * use any of them.
     */
    private static Narrowed narrow(AuditQuery query) {
        StringBuilder where = new StringBuilder("1 = 1");
        Map<String, Object> values = new LinkedHashMap<>();

        if (query.actor() != null && !query.actor().isBlank()) {
            where.append(" and actor = :actor");
            values.put("actor", query.actor());
        }
        if (query.action() != null && !query.action().isBlank()) {
            where.append(" and action = :action");
            values.put("action", query.action());
        }
        if (query.connectionId() != null) {
            where.append(" and connectionId = :connectionId");
            values.put("connectionId", query.connectionId());
        }
        if (query.since() != null) {
            where.append(" and at >= :since");
            values.put("since", query.since());
        }
        return new Narrowed(where.toString(), values);
    }

    /** The distinct actions recorded, so a filter can offer them rather than ask for a guess. */
    public Uni<List<String>> actions() {
        return find("select distinct action from AuditEvent order by action")
                .project(String.class)
                .list();
    }
}
