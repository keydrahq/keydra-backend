package io.keydra.resources;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.util.Map;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Starts one Redis and one Valkey container for the duration of a test class.
 *
 * <p>Both flavors run because Keydra has to behave identically against either, and capability
 * detection is exactly the code that could silently diverge. Host and port are published as config
 * so tests never hardcode them.
 *
 * <p>Follows Cryostat's convention of wrapping Testcontainers in a {@link
 * QuarkusTestResourceLifecycleManager} rather than using JUnit's Testcontainers extension.
 */
public class RedisTargetsResource implements QuarkusTestResourceLifecycleManager {

    public static final String REDIS_HOST = "keydra.test.redis.host";
    public static final String REDIS_PORT = "keydra.test.redis.port";
    public static final String VALKEY_HOST = "keydra.test.valkey.host";
    public static final String VALKEY_PORT = "keydra.test.valkey.port";

    private static final int REDIS_DEFAULT_PORT = 6379;

    private static GenericContainer<?> redis;
    private static GenericContainer<?> valkey;

    /**
     * One of the two stores the tests run against.
     *
     * <p>Named rather than passed around as a host/port pair so a test that crosses stores says
     * which way it is going, and so the command-line client that belongs to each store is chosen
     * with it — Valkey's is not called redis-cli.
     */
    public enum Target {
        REDIS(REDIS_HOST, REDIS_PORT, "redis-cli"),
        VALKEY(VALKEY_HOST, VALKEY_PORT, "valkey-cli");

        private final String hostKey;
        private final String portKey;
        private final String client;

        Target(String hostKey, String portKey, String client) {
            this.hostKey = hostKey;
            this.portKey = portKey;
            this.client = client;
        }

        public String hostKey() {
            return hostKey;
        }

        public String portKey() {
            return portKey;
        }

        private GenericContainer<?> container() {
            return this == REDIS ? redis : valkey;
        }
    }

    /**
     * Runs the store's own command-line client inside its container.
     *
     * <p>Lets a test seed or inspect a keyspace without going back through the application that
     * wrote it, which is the only way an assertion about a migration proves anything.
     */
    public static String exec(Target target, String... command) {
        String[] full = new String[command.length + 1];
        full[0] = target.client;
        System.arraycopy(command, 0, full, 1, command.length);
        try {
            return exec(target.container(), full);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    target.client + " " + String.join(" ", command) + " failed", e);
        }
    }

    /**
     * Empties one database of one store, so a test can assert exactly what a migration put there.
     */
    public static void flush(Target target, int database) {
        exec(target, "-n", String.valueOf(database), "FLUSHDB");
    }

    /**
     * Runs redis-cli inside the container.
     *
     * <p>Lets a test seed or inspect the keyspace directly, so key-browsing assertions are made
     * against data the application did not create and cannot have mis-stored.
     */
    public static String execRedis(String... command) {
        String[] full = new String[command.length + 1];
        full[0] = "redis-cli";
        System.arraycopy(command, 0, full, 1, command.length);
        try {
            return exec(redis, full);
        } catch (IOException | InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "redis-cli " + String.join(" ", command) + " failed", e);
        }
    }

    /**
     * Runs valkey-cli inside the Valkey container.
     *
     * <p>The second end of a migration is only a real second end if it is a different store, and
     * asserting what arrived there means being able to read it without going back through the
     * application that wrote it.
     */
    public static String execValkey(String... command) {
        return exec(Target.VALKEY, command);
    }

    /** Empties the Valkey target so a test can assert exactly what a migration put there. */
    public static void flushValkey() {
        execValkey("FLUSHDB");
    }

    /**
     * Runs one command, retrying once if the connection to the container engine was stale.
     *
     * <p>Rootless Podman closes an idle socket that docker-java's pool still believes is open, and
     * the next request onto it fails with a broken pipe before the container is ever reached. It is
     * not the container's failure and not the test's, and re-sending on a fresh connection is the
     * whole cure. Retried once: a second broken pipe is something else, and hiding it would be
     * worse than the flake.
     */
    private static String exec(GenericContainer<?> container, String[] command)
            throws IOException, InterruptedException {
        try {
            return container.execInContainer(command).getStdout().trim();
        } catch (RuntimeException stale) {
            if (!isBrokenPipe(stale)) {
                throw stale;
            }
            return container.execInContainer(command).getStdout().trim();
        }
    }

    private static boolean isBrokenPipe(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof IOException
                    && String.valueOf(cause.getMessage()).contains("Broken pipe")) {
                return true;
            }
        }
        return false;
    }

    /** Empties the Redis target so a test can assert exact key counts. */
    public static void flushRedis() {
        execRedis("FLUSHDB");
    }

    @Override
    public Map<String, String> start() {
        redis = container("docker.io/library/redis:8-alpine");
        valkey = container("docker.io/valkey/valkey:9-alpine");
        redis.start();
        valkey.start();

        return Map.of(
                REDIS_HOST, redis.getHost(),
                REDIS_PORT, String.valueOf(redis.getMappedPort(REDIS_DEFAULT_PORT)),
                VALKEY_HOST, valkey.getHost(),
                VALKEY_PORT, String.valueOf(valkey.getMappedPort(REDIS_DEFAULT_PORT)));
    }

    @Override
    public void stop() {
        if (redis != null) {
            redis.stop();
        }
        if (valkey != null) {
            valkey.stop();
        }
    }

    private static GenericContainer<?> container(String image) {
        return new GenericContainer<>(DockerImageName.parse(image))
                .withExposedPorts(REDIS_DEFAULT_PORT)
                .waitingFor(Wait.forListeningPort());
    }
}
