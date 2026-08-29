package io.keydra.resources;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Provides the database every {@code @QuarkusTest} needs.
 *
 * <p>Hibernate Reactive has no H2 driver, so tests run against a real PostgreSQL. This is wired
 * explicitly rather than through Dev Services: the container is visible in the test sources, starts
 * once for the whole suite, and behaves the same on a developer machine and in CI.
 *
 * <p>Registered {@code GLOBAL} in {@link io.keydra.AbstractTestBase} — the application cannot boot
 * without a datasource, so every test needs it, not just the ones that touch persistence.
 *
 * <p>The container is a singleton for the JVM and {@link #stop()} deliberately leaves it running.
 * "Global" keeps Quarkus from restarting it between most classes, but not all: a class whose set of
 * test resources differs restarts the application and this along with it. Starting a container is
 * the least reliable thing the suite does — a rootless container engine answers "broken pipe" often
 * enough that a restart in the middle of a run was failing two test classes outright — and starting
 * one is also the slowest. Testcontainers removes it when the JVM exits.
 */
public class PostgresResource implements QuarkusTestResourceLifecycleManager {

    private static final DockerImageName IMAGE =
            DockerImageName.parse("docker.io/library/postgres:17-alpine")
                    .asCompatibleSubstituteFor("postgres");

    /**
     * One container per class loader, and — where the machine allows it — one per developer.
     *
     * <p>The static field is not enough on its own: Quarkus loads this class again in a fresh class
     * loader every time it restarts the application, so "static" lasts exactly as long as one
     * application. {@code withReuse} is what survives that, by asking Testcontainers to adopt an
     * already-running container whose configuration matches instead of creating another. It takes
     * effect only where {@code testcontainers.reuse.enable=true} is set (see docs/DEVELOPMENT.md);
     * everywhere else, including CI, this is an ordinary container.
     */
    private static PostgreSQLContainer postgres;

    private static synchronized PostgreSQLContainer container() {
        if (postgres == null) {
            postgres = new PostgreSQLContainer(IMAGE).withDatabaseName("keydra").withReuse(true);
            postgres.start();
        }
        return postgres;
    }

    @Override
    public Map<String, String> start() {
        PostgreSQLContainer postgres = container();

        // Hibernate Reactive talks to the Vert.x client, which wants a postgresql:// URL
        // rather than the jdbc: form the container reports.
        String reactiveUrl =
                "postgresql://%s:%d/%s"
                        .formatted(
                                postgres.getHost(),
                                postgres.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT),
                                postgres.getDatabaseName());

        return Map.of(
                "quarkus.datasource.reactive.url", reactiveUrl,
                "quarkus.datasource.username", postgres.getUsername(),
                "quarkus.datasource.password", postgres.getPassword(),
                // Published for the one test that runs Flyway, which speaks JDBC. Nothing
                // else in the suite has a JDBC datasource at all.
                "quarkus.datasource.jdbc.url", postgres.getJdbcUrl());
    }

    /**
     * Deliberately empty.
     *
     * <p>Stopping it here would mean starting it again on the next restart, which is the thing this
     * class exists to avoid doing more than once. The schema is dropped and recreated by Hibernate
     * at every application start, so a shared database is not a shared state.
     */
    @Override
    public void stop() {}
}
