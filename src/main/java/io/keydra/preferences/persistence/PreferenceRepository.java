package io.keydra.preferences.persistence;

import io.keydra.preferences.entity.UserPreference;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** The preference rows: one per person per thing they have an opinion about. */
@ApplicationScoped
public class PreferenceRepository {

    /** Everything one person has an opinion about, as the map a browser wants. */
    public Uni<Map<String, String>> allFor(Long userId) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from UserPreference where userId = :userId",
                                                UserPreference.class)
                                        .setParameter("userId", userId)
                                        .getResultList())
                .map(
                        rows ->
                                rows.stream()
                                        .collect(
                                                Collectors.toMap(
                                                        row -> row.name, row -> row.value)));
    }

    /**
     * One named preference belonging to one account, or null when it has never been set.
     *
     * <p>By name rather than by reading the map, because the caller is not the person: the letter
     * an invitation sends needs the language and has no business seeing the rest of what somebody
     * has an opinion about.
     */
    public Uni<String> valueFor(Long userId, String name) {
        UserPreference.Key key = new UserPreference.Key();
        key.userId = userId;
        key.name = name;
        return Panache.getSession()
                .flatMap(session -> session.find(UserPreference.class, key))
                .map(row -> row == null ? null : row.value);
    }

    /**
     * Writes one, whether or not it was there before.
     *
     * <p>A find-then-write rather than an upsert in SQL: Hibernate Reactive has no portable upsert,
     * and the alternative is a native statement per database. Two round trips for something
     * somebody does when they click a switch is a cost nobody will measure.
     */
    public Uni<Void> put(Long userId, String name, String value) {
        UserPreference.Key key = new UserPreference.Key();
        key.userId = userId;
        key.name = name;

        return Panache.getSession()
                .flatMap(
                        session ->
                                session.find(UserPreference.class, key)
                                        .flatMap(
                                                existing -> {
                                                    if (existing != null) {
                                                        existing.value = value;
                                                        return Uni.createFrom().voidItem();
                                                    }
                                                    UserPreference row = new UserPreference();
                                                    row.userId = userId;
                                                    row.name = name;
                                                    row.value = value;
                                                    return session.persist(row);
                                                }));
    }

    /** Forgets one, so the browser's own default applies again. */
    public Uni<Integer> remove(Long userId, String name) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "delete from UserPreference where userId = :userId"
                                                        + " and name = :name")
                                        .setParameter("userId", userId)
                                        .setParameter("name", name)
                                        .executeUpdate());
    }

    /** Everything an account had an opinion about, removed with the account. */
    public Uni<Integer> removeAllFor(Long userId) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "delete from UserPreference where userId = :userId")
                                        .setParameter("userId", userId)
                                        .executeUpdate());
    }

    /** Used by the tests to start from nothing. */
    public Uni<Integer> deleteAll() {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery("delete from UserPreference").executeUpdate());
    }

    /** Names only, for a caller that wants to know what is set without reading the values. */
    public Uni<List<String>> namesFor(Long userId) {
        return allFor(userId).map(all -> List.copyOf(all.keySet()));
    }
}
