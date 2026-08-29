package io.keydra.pubsub.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import io.keydra.connections.ConnectionFixtures;
import io.keydra.resources.RedisTargetsResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Messages have to arrive, so this drives a real server and a real socket. */
@QuarkusTest
@WithTestResource(RedisTargetsResource.class)
class PubSubTest {

    @Inject Vertx vertx;

    @TestHTTPResource("/api/v1/notifications")
    URI notificationsUri;

    private int connectionId;

    @BeforeEach
    void setUp() {
        ConnectionFixtures.deleteAllProfiles();
        connectionId =
                given().contentType(ContentType.JSON)
                        .body(
                                Map.of(
                                        "name",
                                        "pubsub-target",
                                        "host",
                                        ConfigProvider.getConfig()
                                                .getValue(
                                                        RedisTargetsResource.REDIS_HOST,
                                                        String.class),
                                        "port",
                                        ConfigProvider.getConfig()
                                                .getValue(
                                                        RedisTargetsResource.REDIS_PORT,
                                                        Integer.class),
                                        "tls",
                                        false,
                                        "database",
                                        0,
                                        "type",
                                        "STANDALONE"))
                        .when()
                        .post("/api/v1/connections")
                        .then()
                        .statusCode(201)
                        .extract()
                        .path("id");
    }

    @AfterEach
    void closeSubscription() {
        given().when().delete("/api/v1/connections/{id}/pubsub/subscription", connectionId);
    }

    /** Listens on the notification hub and collects envelopes of one category. */
    private LinkedBlockingQueue<JsonObject> watchHub(String category) throws Exception {
        LinkedBlockingQueue<JsonObject> received = new LinkedBlockingQueue<>();
        WebSocketClient client = vertx.createWebSocketClient();
        CompletableFuture<Void> connected = new CompletableFuture<>();

        client.connect(
                        new WebSocketConnectOptions()
                                .setHost(notificationsUri.getHost())
                                .setPort(notificationsUri.getPort())
                                .setURI(notificationsUri.getPath()))
                .onSuccess(
                        socket -> {
                            socket.textMessageHandler(
                                    text -> {
                                        JsonObject envelope = new JsonObject(text);
                                        if (category.equals(envelope.getString("category"))) {
                                            received.add(envelope);
                                        }
                                    });
                            connected.complete(null);
                        })
                .onFailure(connected::completeExceptionally);
        connected.get(10, TimeUnit.SECONDS);
        return received;
    }

    private void subscribe(List<String> channels, List<String> patterns) {
        given().contentType(ContentType.JSON)
                .body(Map.of("channels", channels, "patterns", patterns))
                .when()
                .post("/api/v1/connections/{id}/pubsub/subscription", connectionId)
                .then()
                .statusCode(200);
    }

    @Test
    void deliversAPublishedMessageToTheHub() throws Exception {
        LinkedBlockingQueue<JsonObject> messages = watchHub("ChannelMessage");
        subscribe(List.of("news"), List.of());

        // The subscription is opened asynchronously; publishing before it lands would
        // deliver to nobody, which is why this retries rather than publishing once.
        JsonObject received = publishUntilDelivered(messages, "news", "hello");

        assertThat(received.getJsonObject("payload").getString("channel"), equalTo("news"));
        assertThat(received.getJsonObject("payload").getString("payload"), equalTo("hello"));
    }

    @Test
    void reportsAPatternSubscriptionWithTheMatchedPattern() throws Exception {
        LinkedBlockingQueue<JsonObject> messages = watchHub("ChannelMessage");
        subscribe(List.of(), List.of("news.*"));

        JsonObject received = publishUntilDelivered(messages, "news.sport", "goal");

        assertThat(received.getJsonObject("payload").getString("channel"), equalTo("news.sport"));
        assertThat(received.getJsonObject("payload").getString("pattern"), equalTo("news.*"));
    }

    /** Publishes until the message comes back, so an in-flight subscription is not a flake. */
    private JsonObject publishUntilDelivered(
            LinkedBlockingQueue<JsonObject> messages, String channel, String payload)
            throws InterruptedException {
        for (int attempt = 0; attempt < 40; attempt++) {
            given().contentType(ContentType.JSON)
                    .body(Map.of("channel", channel, "payload", payload))
                    .when()
                    .post("/api/v1/connections/{id}/pubsub/publish", connectionId)
                    .then()
                    .statusCode(200);
            JsonObject received = messages.poll(250, TimeUnit.MILLISECONDS);
            if (received != null) {
                return received;
            }
        }
        throw new AssertionError("No message arrived on " + channel);
    }

    @Test
    void countsWhatItHasReceived() throws Exception {
        LinkedBlockingQueue<JsonObject> messages = watchHub("ChannelMessage");
        subscribe(List.of("counted"), List.of());
        publishUntilDelivered(messages, "counted", "one");

        given().when()
                .get("/api/v1/connections/{id}/pubsub/subscription", connectionId)
                .then()
                .statusCode(200)
                .body("channels", contains("counted"))
                .body("since", notNullValue())
                .body("messagesReceived", greaterThanOrEqualTo(1));
    }

    @Test
    void answersZeroReceiversWithoutTreatingItAsAFailure() {
        given().contentType(ContentType.JSON)
                .body(Map.of("channel", "nobody-listening", "payload", "hello"))
                .when()
                .post("/api/v1/connections/{id}/pubsub/publish", connectionId)
                .then()
                .statusCode(200)
                .body("receivers", equalTo(0));
    }

    @Test
    void replacesRatherThanAddsToAnExistingSubscription() {
        subscribe(List.of("first"), List.of());
        subscribe(List.of("second"), List.of());

        given().when()
                .get("/api/v1/connections/{id}/pubsub/subscription", connectionId)
                .then()
                .statusCode(200)
                // The first channel is gone: the request states what to listen to.
                .body("channels", contains("second"));
    }

    @Test
    void listsEveryOpenSubscription() {
        subscribe(List.of("listed"), List.of());

        given().when()
                .get("/api/v1/subscriptions")
                .then()
                .statusCode(200)
                .body("$", hasSize(1))
                .body("[0].connectionId", equalTo(connectionId));
    }

    @Test
    void saysThereIsNothingToCloseWhenNothingIsOpen() {
        given().when()
                .delete("/api/v1/connections/{id}/pubsub/subscription", connectionId)
                .then()
                .statusCode(404);
    }

    @Test
    void closesASubscriptionWhenAsked() {
        subscribe(List.of("closing"), List.of());

        given().when()
                .delete("/api/v1/connections/{id}/pubsub/subscription", connectionId)
                .then()
                .statusCode(204);

        given().when()
                .get("/api/v1/connections/{id}/pubsub/subscription", connectionId)
                .then()
                .statusCode(404);
    }
}
