package io.keydra.console.ws;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

import io.keydra.connections.ConnectionFixtures;
import io.keydra.resources.RedisTargetsResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import io.vertx.core.Vertx;
import io.vertx.core.http.WebSocket;
import io.vertx.core.http.WebSocketClient;
import io.vertx.core.http.WebSocketConnectOptions;
import io.vertx.core.json.JsonObject;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The console runs real commands, so it is tested against a real server. */
@QuarkusTest
@WithTestResource(RedisTargetsResource.class)
class ConsoleSocketTest {

    @Inject Vertx vertx;

    @TestHTTPResource("/")
    URI root;

    private int connectionId;

    @BeforeEach
    void setUp() {
        ConnectionFixtures.deleteAllProfiles();
        connectionId =
                given().contentType(ContentType.JSON)
                        .body(
                                Map.of(
                                        "name",
                                        "console-target",
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
        RedisTargetsResource.flushRedis();
    }

    /** Opens a console socket and returns the replies it receives. */
    private Session open() throws Exception {
        LinkedBlockingQueue<JsonObject> received = new LinkedBlockingQueue<>();
        WebSocketClient client = vertx.createWebSocketClient();
        CompletableFuture<WebSocket> connected = new CompletableFuture<>();

        client.connect(
                        new WebSocketConnectOptions()
                                .setHost(root.getHost())
                                .setPort(root.getPort())
                                .setURI("/api/v1/connections/" + connectionId + "/console"))
                .onSuccess(
                        socket -> {
                            socket.textMessageHandler(text -> received.add(new JsonObject(text)));
                            connected.complete(socket);
                        })
                .onFailure(connected::completeExceptionally);
        return new Session(connected.get(10, TimeUnit.SECONDS), received);
    }

    private record Session(WebSocket socket, LinkedBlockingQueue<JsonObject> received) {

        JsonObject run(String id, String line) throws InterruptedException {
            socket.writeTextMessage(new JsonObject().put("id", id).put("line", line).encode());
            JsonObject result = received.poll(10, TimeUnit.SECONDS);
            if (result == null) {
                throw new AssertionError("No reply to: " + line);
            }
            return result;
        }
    }

    @Test
    void runsACommandAndAnswersOnTheSameSocket() throws Exception {
        Session session = open();

        JsonObject set = session.run("1", "SET greeting hello");
        assertThat(set.getString("id"), equalTo("1"));
        assertThat(set.getJsonObject("value").getString("kind"), equalTo("text"));
        assertThat(set.getJsonObject("value").getString("value"), equalTo("OK"));

        JsonObject get = session.run("2", "GET greeting");
        assertThat(get.getJsonObject("value").getString("value"), equalTo("hello"));
    }

    @Test
    void keepsAQuotedArgumentWhole() throws Exception {
        Session session = open();

        session.run("1", "SET greeting \"hello world\"");
        JsonObject get = session.run("2", "GET greeting");

        assertThat(get.getJsonObject("value").getString("value"), equalTo("hello world"));
    }

    @Test
    void distinguishesAnIntegerReplyFromAString() throws Exception {
        Session session = open();

        JsonObject reply = session.run("1", "INCR counter");

        assertThat(reply.getJsonObject("value").getString("kind"), equalTo("number"));
        assertThat(reply.getJsonObject("value").getLong("value"), equalTo(1L));
    }

    @Test
    void rendersAnArrayReplyAsASequence() throws Exception {
        Session session = open();
        session.run("1", "RPUSH queue a b c");

        JsonObject reply = session.run("2", "LRANGE queue 0 -1");

        assertThat(
                reply.encode(),
                reply.getJsonObject("value").getString("kind"),
                equalTo("sequence"));
        assertThat(reply.getJsonObject("value").getJsonArray("items").size(), equalTo(3));
    }

    @Test
    void keepsFieldNamesOnAnAssociativeReply() throws Exception {
        Session session = open();
        session.run("1", "HSET user name alice");

        JsonObject reply = session.run("2", "HGETALL user");

        // The client nests each field as its own pair, so the names survive without anyone
        // having to guess that this array was meant as a map.
        JsonObject pair = reply.getJsonObject("value").getJsonArray("items").getJsonObject(0);
        assertThat(pair.getString("kind"), equalTo("sequence"));
        assertThat(pair.getJsonArray("items").getJsonObject(0).getString("value"), equalTo("name"));
        assertThat(
                pair.getJsonArray("items").getJsonObject(1).getString("value"), equalTo("alice"));
    }

    @Test
    void doesNotMistakeAnEvenLengthArrayForAMap() throws Exception {
        Session session = open();
        session.run("1", "RPUSH pair a b");

        // Two elements is exactly the shape a map guess gets wrong.
        JsonObject reply = session.run("2", "LRANGE pair 0 -1");

        assertThat(reply.getJsonObject("value").getString("kind"), equalTo("sequence"));
        assertThat(reply.getJsonObject("value").getJsonArray("items").size(), equalTo(2));
    }

    @Test
    void tellsTheUserWhatWentWrongInsteadOfDroppingTheSocket() throws Exception {
        Session session = open();
        session.run("1", "SET greeting hello");

        // A type error is an answer, and the session must survive it.
        JsonObject reply = session.run("2", "LPUSH greeting x");

        assertThat(reply.getJsonObject("value").getString("kind"), equalTo("error"));
        assertThat(reply.getJsonObject("value").getString("message"), containsString("WRONGTYPE"));

        // Still usable afterwards.
        assertThat(
                session.run("3", "GET greeting").getJsonObject("value").getString("value"),
                equalTo("hello"));
    }

    @Test
    void refusesACommandThatWouldStallTheServer() throws Exception {
        Session session = open();

        JsonObject reply = session.run("1", "KEYS *");

        assertThat(reply.getJsonObject("value").getString("kind"), equalTo("error"));
        assertThat(reply.getJsonObject("value").getString("message"), containsString("KEYS"));
    }

    @Test
    void reportsAnUnbalancedQuoteWithoutRunningAnything() throws Exception {
        Session session = open();

        JsonObject reply = session.run("1", "SET k \"unfinished");

        assertThat(reply.getJsonObject("value").getString("kind"), equalTo("error"));
        // Nothing reached the server, so the key does not exist.
        assertThat(
                session.run("2", "EXISTS k").getJsonObject("value").getLong("value"), equalTo(0L));
    }

    @Test
    void remembersWhatWasRunButNotWhatWasRefused() throws Exception {
        Session session = open();
        session.run("1", "SET remembered yes");
        session.run("2", "KEYS *");
        session.run("3", "SET k \"unfinished");

        java.util.List<String> lines =
                given().when()
                        .get("/api/v1/connections/{id}/console/history", connectionId)
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("line");

        assertThat(lines, hasItem("SET remembered yes"));
        // A refused command and a line that never parsed would be useless to recall.
        assertThat(lines, not(hasItem("KEYS *")));
        assertThat(lines, not(hasItem("SET k \"unfinished")));
    }

    @Test
    void forgetsHistoryWhenAsked() throws Exception {
        Session session = open();
        session.run("1", "SET remembered yes");

        given().when()
                .delete("/api/v1/connections/{id}/console/history", connectionId)
                .then()
                .statusCode(204);

        given().when()
                .get("/api/v1/connections/{id}/console/history", connectionId)
                .then()
                .statusCode(200)
                .body("$", hasSize(0));
    }

    @Test
    void publishesWhatItWillRefuse() {
        given().when()
                .get("/api/v1/connections/{id}/console/denied-commands", connectionId)
                .then()
                .statusCode(200)
                .body("$", hasItem("keys"))
                .body("$", hasItem("monitor"));
    }

    @Test
    void ordersHistoryMostRecentFirst() throws Exception {
        Session session = open();
        session.run("1", "SET first 1");
        session.run("2", "SET second 2");

        java.util.List<String> lines =
                given().when()
                        .get("/api/v1/connections/{id}/console/history", connectionId)
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("line");

        assertThat(lines, contains("SET second 2", "SET first 1"));
    }
}
