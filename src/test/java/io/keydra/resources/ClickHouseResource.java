package io.keydra.resources;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * A real ClickHouse, for the one test class that needs one.
 *
 * <p>Real rather than a stub, because the thing worth testing is whether ClickHouse accepts what
 * Keydra sends it: the schema, the insert format and the bucketing query are all strings this
 * application composes, and a fake HTTP endpoint would agree with every one of them, including the
 * wrong ones.
 *
 * <p>Started only by the class that uses it — the other four hundred tests do not need a column
 * store, and starting one is the slowest thing this suite can be asked to do.
 */
public class ClickHouseResource implements QuarkusTestResourceLifecycleManager {

    /** The same release the development manifest runs, so the two agree about what works. */
    private static final DockerImageName IMAGE =
            DockerImageName.parse("docker.io/clickhouse/clickhouse-server:25.8-alpine");

    private static GenericContainer<?> clickhouse;

    private static synchronized GenericContainer<?> container() {
        if (clickhouse == null) {
            clickhouse =
                    new GenericContainer<>(IMAGE)
                            .withExposedPorts(8123)
                            // One user, no password: the container is reachable from this JVM
                            // and nowhere else.
                            .withEnv("CLICKHOUSE_SKIP_USER_SETUP", "1")
                            .waitingFor(Wait.forHttp("/ping").forPort(8123))
                            .withReuse(true);
            clickhouse.start();
        }
        return clickhouse;
    }

    @Override
    public Map<String, String> start() {
        GenericContainer<?> running = container();
        String url = "http://%s:%d".formatted(running.getHost(), running.getMappedPort(8123));
        return Map.of(
                "keydra.metrics.clickhouse.enabled", "true",
                "keydra.metrics.clickhouse.url", url,
                // Small enough that a test does not have to wait for a batch to fill.
                "keydra.metrics.clickhouse.batch-size", "1");
    }

    @Override
    public void stop() {
        // Left running for the same reason PostgresResource leaves its own: starting a
        // container is the least reliable and slowest thing in this suite, and Testcontainers
        // removes it when the JVM exits.
    }
}
