package io.keydra.alerts.rest;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;

import com.sun.net.httpserver.HttpServer;
import io.keydra.alerts.AlertFixtures;
import io.keydra.alerts.FakeSmtp;
import io.keydra.connections.ConnectionFixtures;
import io.keydra.resources.RedisTargetsResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Where alerts are sent, and what is never sent back.
 *
 * <p>Against a real HTTP server rather than a mock, for the reason the backups are tested against a
 * real directory: the mistakes in this feature are in what actually goes over the wire — the body a
 * chat tool will or will not render, the header that carries the token, the query string that Camel
 * would otherwise read as its own options.
 */
@QuarkusTest
@WithTestResource(RedisTargetsResource.class)
class AlertDeliveriesTest {

    private HttpServer webhook;
    private final AtomicReference<String> received = new AtomicReference<>();
    private final AtomicReference<String> receivedHeader = new AtomicReference<>();
    private final AtomicReference<String> receivedQuery = new AtomicReference<>();
    private int status = 200;

    private Long target;

    @BeforeEach
    void setUp() throws IOException {
        AlertFixtures.deleteEveryRule();
        ConnectionFixtures.deleteAllProfiles();

        String host =
                ConfigProvider.getConfig().getValue(RedisTargetsResource.REDIS_HOST, String.class);
        int port =
                ConfigProvider.getConfig().getValue(RedisTargetsResource.REDIS_PORT, Integer.class);
        target = ConnectionFixtures.createProfile("payments-cache", host, port);

        webhook = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        webhook.createContext(
                "/hook",
                exchange -> {
                    try (InputStream body = exchange.getRequestBody()) {
                        received.set(new String(body.readAllBytes(), StandardCharsets.UTF_8));
                    }
                    receivedHeader.set(exchange.getRequestHeaders().getFirst("X-Token"));
                    receivedQuery.set(exchange.getRequestURI().getQuery());
                    exchange.sendResponseHeaders(status, -1);
                    exchange.close();
                });
        webhook.start();
    }

    @AfterEach
    void tearDown() {
        webhook.stop(0);
    }

    @Test
    void aWebhookAddressIsNeverSentBack() {
        int id = createWebhook("on-call", hookUrl(), "X-Token", "a-shared-secret");

        given().when()
                .get("/api/v1/alert-deliveries")
                .then()
                .statusCode(200)
                .body("[0].id", equalTo(id))
                // The address carries the token in its path, so it is reported the way a
                // password is: whether one exists, and its host, and nothing else.
                .body("[0].hasUrl", equalTo(true))
                .body("[0].urlHost", equalTo("127.0.0.1"))
                .body("[0].hasSecret", equalTo(true))
                .body("[0].describedAs", equalTo("127.0.0.1"));

        String whole =
                given().when().get("/api/v1/alert-deliveries").then().extract().body().asString();
        assertThat(whole, not(containsString("/hook")));
        assertThat(whole, not(containsString("a-shared-secret")));
    }

    @Test
    void aCheckSendsTheMessageAnAlertWouldSend() {
        int id = createWebhook("on-call", hookUrl(), "X-Token", "a-shared-secret");

        given().when()
                .post("/api/v1/alert-deliveries/" + id + "/check")
                .then()
                .statusCode(200)
                .body("working", equalTo(true))
                .body("detail", containsString("200"));

        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(received.get(), notNullValue()));
        String body = received.get();
        // Both shapes, because Slack renders one and Discord renders the other, and a body
        // with only one of them arrives as an empty message in the service that wanted the
        // other.
        assertThat(body, containsString("\"text\""));
        assertThat(body, containsString("\"content\""));
        assertThat(body, containsString("\"threshold\":90.0"));
        assertThat(receivedHeader.get(), is("a-shared-secret"));
        // The query travels as a header rather than in the endpoint URI: Camel reads an
        // endpoint's query as its own options, and a webhook that carries a token in one
        // would be refused as an unknown setting.
        assertThat(receivedQuery.get(), is("token=abc"));
    }

    @Test
    void aWebhookThatRefusesSaysSoRatherThanLookingLikeItWorked() {
        status = 403;
        int id = createWebhook("on-call", hookUrl(), null, null);

        given().when()
                .post("/api/v1/alert-deliveries/" + id + "/check")
                .then()
                .statusCode(200)
                .body("working", equalTo(false))
                .body("detail", containsString("403"));
    }

    @Test
    void anAddressThatIsNotOneIsRefusedWhileSomebodyIsLookingAtIt() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "typo");
        body.put("kind", "WEBHOOK");
        body.put("url", "hooks.example.com/services/abc");

        given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/alert-deliveries")
                .then()
                .statusCode(409)
                .body("message", containsString("http"));
    }

    @Test
    void anEditThatLeavesTheAddressOutKeepsIt() {
        int id = createWebhook("on-call", hookUrl(), "X-Token", "a-shared-secret");

        Map<String, Object> renamed = new HashMap<>();
        renamed.put("name", "on-call (europe)");
        renamed.put("kind", "WEBHOOK");

        given().contentType(ContentType.JSON)
                .body(renamed)
                .when()
                .put("/api/v1/alert-deliveries/" + id)
                .then()
                .statusCode(200)
                // The form arrives with the address empty because the API never returned it.
                // Treating that as "clear it" would silence every rule pointing here.
                .body("hasUrl", equalTo(true));

        given().when()
                .post("/api/v1/alert-deliveries/" + id + "/check")
                .then()
                .body("working", equalTo(true));
    }

    @Test
    void aDeliveryStillBeingUsedIsNotRemoved() {
        int id = createWebhook("on-call", hookUrl(), null, null);

        Map<String, Object> rule = new HashMap<>();
        rule.put("name", "Memory");
        rule.put("connectionId", target);
        rule.put("metric", "MEMORY_USED_BYTES");
        rule.put("threshold", 500.0);
        rule.put("deliveryIds", java.util.List.of(id));
        given().contentType(ContentType.JSON)
                .body(rule)
                .when()
                .post("/api/v1/alerts?connectionId=" + target)
                .then()
                .statusCode(201);

        given().when()
                .delete("/api/v1/alert-deliveries/" + id)
                .then()
                .statusCode(409)
                .body("message", containsString("rule sends here"));

        given().when()
                .get("/api/v1/alert-deliveries")
                .then()
                .body("", hasSize(1))
                .body("[0].usedByRules", equalTo(1));
    }

    @Test
    void mailArrivesWithASubjectSomebodyCanReadWithoutOpeningIt() throws Exception {
        try (FakeSmtp smtp = new FakeSmtp()) {
            Map<String, Object> body = new HashMap<>();
            body.put("name", "the on-call inbox");
            body.put("kind", "EMAIL");
            body.put("smtpHost", "127.0.0.1");
            body.put("smtpPort", smtp.port());
            body.put("smtpTls", false);
            body.put("fromAddress", "keydra@example.test");
            body.put("toAddresses", "on-call@example.test");
            int id =
                    given().contentType(ContentType.JSON)
                            .body(body)
                            .when()
                            .post("/api/v1/alert-deliveries")
                            .then()
                            .statusCode(201)
                            .extract()
                            .path("id");

            given().when()
                    .post("/api/v1/alert-deliveries/" + id + "/check")
                    .then()
                    .statusCode(200)
                    .body("working", equalTo(true));

            await().atMost(Duration.ofSeconds(10))
                    .untilAsserted(() -> assertThat(smtp.messages(), hasSize(1)));
            String message = smtp.messages().get(0);
            // The subject is what somebody sees on a phone at four in the morning, and it has
            // to name the target before anything else is read.
            assertThat(message, containsString("Subject: [Keydra]"));
            assertThat(message, containsString("on-call@example.test"));
            assertThat(message, containsString("91%"));
        }
    }

    @Test
    void mailNeedsTheThreeThingsAServerWillAskFor() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "the inbox");
        body.put("kind", "EMAIL");
        body.put("smtpHost", "127.0.0.1");
        body.put("smtpPort", 1025);

        given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/alert-deliveries")
                .then()
                .statusCode(409)
                .body("message", containsString("send to"));
    }

    // --- Helpers -----------------------------------------------------------

    private String hookUrl() {
        return "http://127.0.0.1:" + webhook.getAddress().getPort() + "/hook?token=abc";
    }

    private int createWebhook(String name, String url, String headerName, String headerValue) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("kind", "WEBHOOK");
        body.put("enabled", true);
        body.put("url", url);
        if (headerName != null) {
            body.put("headerName", headerName);
            body.put("headerValue", headerValue);
        }
        return given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/alert-deliveries")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }
}
