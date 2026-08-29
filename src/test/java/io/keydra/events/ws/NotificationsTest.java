package io.keydra.events.ws;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.keydra.connections.ConnectionFixtures;
import io.keydra.events.dto.NotificationCategory;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The notification hub is what lets the UI drop polling, so its envelope shape is contract. */
@QuarkusTest
class NotificationsTest {

    @Inject Vertx vertx;

    @TestHTTPResource("/api/v1/notifications")
    URI notificationsUri;

    @BeforeEach
    void clean() {
        ConnectionFixtures.deleteAllProfiles();
    }

    @Test
    void broadcastsAnEnvelopeWhenAProfileIsCreated() throws Exception {
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
            given().contentType(ContentType.JSON)
                    .body(
                            Map.of(
                                    "name",
                                    "notified",
                                    "host",
                                    "localhost",
                                    "port",
                                    6379,
                                    "tls",
                                    false,
                                    "database",
                                    0,
                                    "type",
                                    "STANDALONE"))
                    .when()
                    .post("/api/v1/connections")
                    .then()
                    .statusCode(201);

            String message = received.poll(10, TimeUnit.SECONDS);
            assertTrue(message != null, "No notification arrived within 10s");

            JsonObject envelope = new JsonObject(message);
            assertThat(
                    envelope.getString("category"),
                    equalTo(NotificationCategory.CONNECTION_CREATED));
            assertThat(envelope.getString("ts"), notNullValue());
            // The id and nothing else. A lifecycle envelope reaches every socket, and a target's
            // name, host and port are the things a filtered list is careful about — so what goes
            // out is the fact that something changed, and every listener asks again through the
            // query that does the filtering.
            assertThat(envelope.getJsonObject("payload").getLong("id"), notNullValue());
            assertThat(envelope.getJsonObject("payload").getString("name"), equalTo(null));
            assertThat(envelope.getJsonObject("payload").getString("host"), equalTo(null));
            assertThat(envelope.getJsonObject("payload").getString("password"), equalTo(null));
        } finally {
            client.close();
        }
    }
}
