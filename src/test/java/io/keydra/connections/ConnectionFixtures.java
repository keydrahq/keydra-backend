package io.keydra.connections;

import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.entity.ConnectionType;
import io.keydra.connections.persistence.ConnectionProfileRepository;
import io.keydra.engine.EngineType;
import io.quarkus.arc.Arc;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.vertx.VertxContextSupport;

/**
 * Test-data helpers for the connections domain.
 *
 * <p>Hibernate Reactive refuses to run outside a Vert.x context, and a JUnit thread is not one.
 * {@link VertxContextSupport#subscribeAndAwait} bridges the two, which is what lets ordinary
 * {@code @BeforeEach} setup drive reactive persistence without turning every HTTP test into a
 * {@code UniAsserter} test.
 */
public final class ConnectionFixtures {

    private ConnectionFixtures() {}

    /**
     * Inserts a profile directly, returning its id.
     *
     * <p>Bypasses the REST layer deliberately. Creating a profile over HTTP needs the admin role,
     * and a test about what a viewer may do should not have to be an admin first — nor should the
     * setup for an unauthenticated test have to authenticate.
     */
    public static Long createProfile(String name, String host, int port) {
        ConnectionProfileRepository repository =
                Arc.container().instance(ConnectionProfileRepository.class).get();
        ConnectionProfile profile = new ConnectionProfile();
        profile.name = name;
        profile.host = host;
        profile.port = port;
        profile.tls = false;
        profile.database = 0;
        profile.type = ConnectionType.STANDALONE;
        profile.engine = EngineType.RESP;
        try {
            VertxContextSupport.subscribeAndAwait(
                    () -> Panache.withTransaction(() -> repository.persist(profile)));
        } catch (Throwable e) {
            throw new IllegalStateException("Could not create connection profile", e);
        }
        return profile.id;
    }

    /** Turns on "name this one before you empty it" for a profile that already exists. */
    public static void guard(Long id) {
        flip(id, profile -> profile.guarded = true);
    }

    /**
     * Turns on "nobody empties this one alone" for a profile that already exists.
     *
     * <p>Written straight to the column rather than through the API for the reason {@link
     * #createProfile} is: a test about what an operator may do should not have to be an
     * administrator first in order to arrange the target they will be refused on.
     */
    public static void requireApproval(Long id) {
        flip(id, profile -> profile.requiresApproval = true);
    }

    private static void flip(Long id, java.util.function.Consumer<ConnectionProfile> change) {
        ConnectionProfileRepository repository =
                Arc.container().instance(ConnectionProfileRepository.class).get();
        try {
            VertxContextSupport.subscribeAndAwait(
                    () ->
                            Panache.withTransaction(
                                    () -> repository.findById(id).invoke(change::accept)));
        } catch (Throwable e) {
            throw new IllegalStateException("Could not guard connection profile " + id, e);
        }
    }

    /**
     * One column as the database holds it, converter and all bypassed.
     *
     * <p>The only way to see the stored form of an encrypted value: the converter exists precisely
     * so nothing above it can, which is right everywhere except in a test about the envelope
     * itself.
     */
    public static String rawColumn(String table, String column) {
        try {
            return VertxContextSupport.subscribeAndAwait(
                    () ->
                            Panache.withSession(
                                    () ->
                                            Panache.getSession()
                                                    .flatMap(
                                                            session ->
                                                                    session.createNativeQuery(
                                                                                    "select "
                                                                                            + column
                                                                                            + " from"
                                                                                            + " "
                                                                                            + table
                                                                                            + " where"
                                                                                            + " "
                                                                                            + column
                                                                                            + " is not"
                                                                                            + " null"
                                                                                            + " order"
                                                                                            + " by id",
                                                                                    String.class)
                                                                            .getResultList())
                                                    .map(
                                                            rows ->
                                                                    rows.isEmpty()
                                                                            ? null
                                                                            : rows.get(0))));
        } catch (Throwable e) {
            throw new IllegalStateException("Could not read " + table + "." + column, e);
        }
    }

    /** Rewrites one column's stored form, for tests about what the envelope looks like. */
    public static void setRawColumn(
            String table, String column, long id, java.util.function.UnaryOperator<String> change) {
        try {
            VertxContextSupport.subscribeAndAwait(
                    () ->
                            Panache.withTransaction(
                                    () ->
                                            Panache.getSession()
                                                    .flatMap(
                                                            session ->
                                                                    session.createNativeQuery(
                                                                                    "select "
                                                                                            + column
                                                                                            + " from"
                                                                                            + " "
                                                                                            + table
                                                                                            + " where"
                                                                                            + " id ="
                                                                                            + " :id",
                                                                                    String.class)
                                                                            .setParameter("id", id)
                                                                            .getSingleResult())
                                                    .flatMap(
                                                            current ->
                                                                    Panache.getSession()
                                                                            .flatMap(
                                                                                    session ->
                                                                                            session.createNativeQuery(
                                                                                                            "update"
                                                                                                                + " "
                                                                                                                    + table
                                                                                                                    + " set "
                                                                                                                    + column
                                                                                                                    + " = :value"
                                                                                                                    + " where"
                                                                                                                    + " id ="
                                                                                                                    + " :id")
                                                                                                    .setParameter(
                                                                                                            "value",
                                                                                                            change
                                                                                                                    .apply(
                                                                                                                            current))
                                                                                                    .setParameter(
                                                                                                            "id",
                                                                                                            id)
                                                                                                    .executeUpdate()))));
        } catch (Throwable e) {
            throw new IllegalStateException("Could not write " + table + "." + column, e);
        }
    }

    /**
     * Empties the migration history.
     *
     * <p>Needed by anything that sweeps: a row left running by one test is a row the next test's
     * handover will pick up, and a count that was meant to be one is then two.
     */
    public static void deleteAllMigrations() {
        try {
            VertxContextSupport.subscribeAndAwait(
                    () ->
                            Panache.withTransaction(
                                    () ->
                                            Panache.getSession()
                                                    .flatMap(
                                                            session ->
                                                                    session.createQuery(
                                                                                    "delete from"
                                                                                        + " MigrationRun")
                                                                            .executeUpdate())));
        } catch (Throwable e) {
            throw new IllegalStateException("Could not clear the migration history", e);
        }
    }

    /** Empties the table so each test starts from a known state. */
    public static void deleteAllProfiles() {
        ConnectionProfileRepository repository =
                Arc.container().instance(ConnectionProfileRepository.class).get();
        try {
            VertxContextSupport.subscribeAndAwait(
                    () -> Panache.withTransaction(repository::deleteAll));
        } catch (Throwable e) {
            throw new IllegalStateException("Could not clear connection profiles", e);
        }
    }
}
