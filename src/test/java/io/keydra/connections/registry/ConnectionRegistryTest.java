package io.keydra.connections.registry;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

import io.keydra.connections.dto.ConnectionState;
import io.keydra.connections.dto.ConnectionStatus;
import io.keydra.connections.dto.ServerInfo;
import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.entity.ConnectionType;
import io.keydra.events.dto.NotificationCategory;
import io.keydra.resources.RedisTargetsResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

/** Probes real Redis and Valkey servers; capability detection is the point of the exercise. */
@QuarkusTest
@WithTestResource(RedisTargetsResource.class)
class ConnectionRegistryTest {

    @Inject ConnectionRegistry registry;

    @Inject Vertx vertx;

    @TestHTTPResource("/api/v1/notifications")
    URI notificationsUri;

    /*
     * Looked up lazily instead of injected with @ConfigProperty: Quarkus validates
     * every bean's injected config at startup — test classes included — so an
     * injected field here would break unrelated tests that run without this resource.
     */
    private static String host(String key) {
        return ConfigProvider.getConfig().getValue(key, String.class);
    }

    private static int port(String key) {
        return ConfigProvider.getConfig().getValue(key, Integer.class);
    }

    private static ConnectionProfile profile(String name, String host, int port) {
        ConnectionProfile profile = new ConnectionProfile();
        profile.name = name;
        profile.host = host;
        profile.port = port;
        profile.type = ConnectionType.STANDALONE;
        return profile;
    }

    @Test
    void detectsRedisFlavorAndVersion() {
        ConnectionStatus status =
                registry.test(
                                profile(
                                        "redis",
                                        host(RedisTargetsResource.REDIS_HOST),
                                        port(RedisTargetsResource.REDIS_PORT)))
                        .await()
                        .atMost(Duration.ofSeconds(20));

        assertThat(status.state(), equalTo(ConnectionState.UP));
        assertThat(status.server(), notNullValue());
        assertThat(status.server().flavor(), equalTo(ServerInfo.FLAVOR_REDIS));
        assertThat(status.server().version(), startsWith("8."));
        assertThat(status.server().mode(), equalTo("standalone"));
    }

    @Test
    void detectsValkeyFlavorAndVersion() {
        ConnectionStatus status =
                registry.test(
                                profile(
                                        "valkey",
                                        host(RedisTargetsResource.VALKEY_HOST),
                                        port(RedisTargetsResource.VALKEY_PORT)))
                        .await()
                        .atMost(Duration.ofSeconds(20));

        assertThat(status.state(), equalTo(ConnectionState.UP));
        assertThat(status.server().flavor(), equalTo(ServerInfo.FLAVOR_VALKEY));
        assertThat(status.server().version(), startsWith("9."));
        // Valkey must not be mistaken for Redis even though it reports redis_version
        assertThat(status.server().flavor(), not(equalTo(ServerInfo.FLAVOR_REDIS)));
    }

    @Test
    void reportsDownForAnUnreachableTarget() {
        ConnectionStatus status =
                registry.test(profile("nowhere", "127.0.0.1", 1))
                        .await()
                        .atMost(Duration.ofSeconds(20));

        assertThat(status.state(), equalTo(ConnectionState.DOWN));
        assertThat(status.message(), notNullValue());
        assertThat(status.server(), org.hamcrest.Matchers.nullValue());
    }

    @Test
    void testingAProfileDoesNotRegisterIt() {
        // "test connection" must stay side-effect free so it works for unsaved profiles
        ConnectionProfile profile =
                profile(
                        "probe-only",
                        host(RedisTargetsResource.REDIS_HOST),
                        port(RedisTargetsResource.REDIS_PORT));
        profile.id = 987654L;

        registry.test(profile).await().atMost(Duration.ofSeconds(20));

        assertThat(registry.registeredIds().contains(987654L), equalTo(false));
        assertThat(registry.status(987654L).state(), equalTo(ConnectionState.UNKNOWN));
    }

    /**
     * The status events broadcast while the given work runs.
     *
     * <p>Read off the socket rather than from a stubbed hub: what a listener receives is the thing
     * being asserted, and a fake would let the payload change shape without the test noticing.
     */
    private List<JsonObject> statusEventsWhile(Runnable work) throws Exception {
        LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
        WebSocketClient client = vertx.createWebSocketClient();
        CompletableFuture<Void> connected = new CompletableFuture<>();
        client.connect(
                        new WebSocketConnectOptions()
                                .setHost(notificationsUri.getHost())
                                .setPort(notificationsUri.getPort())
                                .setURI(notificationsUri.getPath()))
                .onSuccess(
                        socket -> {
                            socket.textMessageHandler(received::add);
                            connected.complete(null);
                        })
                .onFailure(connected::completeExceptionally);
        connected.get(10, TimeUnit.SECONDS);

        try {
            work.run();
            List<JsonObject> statuses = new ArrayList<>();
            String message;
            // Drained until it goes quiet: one call produces several events and the count
            // is not the thing under test.
            while ((message = received.poll(3, TimeUnit.SECONDS)) != null) {
                JsonObject envelope = new JsonObject(message);
                if (NotificationCategory.CONNECTION_STATUS_CHANGED.equals(
                        envelope.getString("category"))) {
                    statuses.add(envelope.getJsonObject("payload"));
                }
            }
            return statuses;
        } finally {
            client.close();
        }
    }

    private static JsonObject lastIn(List<JsonObject> events, String state) {
        return events.stream()
                .filter(event -> state.equals(event.getJsonObject("status").getString("state")))
                .reduce((first, second) -> second)
                .orElse(null);
    }

    @Test
    void theFirstNewsOfATargetSaysItFollowedNothing() throws Exception {
        // Signing in probes every target for the first time, and every one of them reaches
        // "up" from nothing. Announcing that as a recovery greeted people with a success
        // toast per target that had never been away.
        ConnectionProfile profile =
                profile(
                        "first-news",
                        host(RedisTargetsResource.REDIS_HOST),
                        port(RedisTargetsResource.REDIS_PORT));
        profile.id = 424242L;

        List<JsonObject> events =
                statusEventsWhile(
                        () -> registry.refresh(profile).await().atMost(Duration.ofSeconds(20)));

        JsonObject up = lastIn(events, ConnectionState.UP.name());
        assertThat(up, notNullValue());
        assertThat(up.getString("previousState"), org.hamcrest.Matchers.nullValue());
        registry.close(profile.id);
    }

    @Test
    void aTargetThatComesBackSaysWhatItCameBackFrom() throws Exception {
        // The same id, moved from somewhere unreachable to a real server: down, then up,
        // which is the one sequence worth interrupting somebody about.
        ConnectionProfile broken = profile("recovering", "127.0.0.1", 1);
        broken.id = 424243L;
        registry.refresh(broken).await().atMost(Duration.ofSeconds(20));

        ConnectionProfile fixed =
                profile(
                        "recovering",
                        host(RedisTargetsResource.REDIS_HOST),
                        port(RedisTargetsResource.REDIS_PORT));
        fixed.id = broken.id;

        List<JsonObject> events =
                statusEventsWhile(
                        () -> registry.refresh(fixed).await().atMost(Duration.ofSeconds(20)));

        JsonObject up = lastIn(events, ConnectionState.UP.name());
        assertThat(up, notNullValue());
        assertThat(up.getString("previousState"), equalTo(ConnectionState.DOWN.name()));
        registry.close(fixed.id);
    }

    @Test
    void aRecheckOfSomethingAlreadyUpDoesNotLookLikeAReturn() throws Exception {
        // A re-check passes through "asking" on its way back to "up". If that counted as the
        // previous state, every refresh would read as a target that had gone and returned.
        ConnectionProfile profile =
                profile(
                        "steady",
                        host(RedisTargetsResource.REDIS_HOST),
                        port(RedisTargetsResource.REDIS_PORT));
        profile.id = 424244L;
        registry.refresh(profile).await().atMost(Duration.ofSeconds(20));

        List<JsonObject> events =
                statusEventsWhile(
                        () -> registry.refresh(profile).await().atMost(Duration.ofSeconds(20)));

        for (JsonObject event : events) {
            assertThat(
                    event.getString("previousState"),
                    not(equalTo(ConnectionState.CONNECTING.name())));
        }
        registry.close(profile.id);
    }
}
