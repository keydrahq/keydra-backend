package io.keydra.connections.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.keydra.AbstractTestBase;
import io.keydra.connections.ConnectionFixtures;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestHTTPEndpoint(Connections.class)
class ConnectionsTest extends AbstractTestBase {

    private static Map<String, Object> request(String name) {
        return Map.of(
                "name",
                name,
                "host",
                "localhost",
                "port",
                6379,
                "password",
                "s3cr3t",
                "tls",
                false,
                "database",
                0,
                "type",
                "STANDALONE",
                "notes",
                "created by test");
    }

    /** The same body with extra fields on top, for the tests that care about one of them. */
    private static Map<String, Object> request(String name, Map<String, Object> extra) {
        Map<String, Object> body = new java.util.HashMap<>(request(name));
        body.putAll(extra);
        return body;
    }

    @BeforeEach
    void clean() {
        ConnectionFixtures.deleteAllProfiles();
    }

    @Test
    void startsEmpty() {
        given().when().get().then().statusCode(200).body("size()", is(0));
    }

    @Test
    void createsAProfileAndNeverEchoesThePassword() {
        String body =
                given().contentType(ContentType.JSON)
                        .body(request("local-redis"))
                        .when()
                        .post()
                        .then()
                        .statusCode(201)
                        .body("id", notNullValue())
                        .body("name", equalTo("local-redis"))
                        .body("host", equalTo("localhost"))
                        .body("port", equalTo(6379))
                        // the secret is acknowledged but never returned
                        .body("hasPassword", is(true))
                        .body("password", nullValue())
                        .body("status.state", equalTo("UNKNOWN"))
                        .extract()
                        .asString();

        // belt and braces: the raw payload must not contain the secret anywhere
        org.hamcrest.MatcherAssert.assertThat(
                body, not(org.hamcrest.Matchers.containsString("s3cr3t")));
    }

    @Test
    void rejectsADuplicateName() {
        given().contentType(ContentType.JSON)
                .body(request("dupe"))
                .when()
                .post()
                .then()
                .statusCode(201);

        given().contentType(ContentType.JSON)
                .body(request("dupe"))
                .when()
                .post()
                .then()
                .statusCode(409)
                .body("message", not(blankOrNullString()));
    }

    @Test
    void rejectsAnInvalidPayload() {
        given().contentType(ContentType.JSON)
                .body(Map.of("name", "", "host", "localhost", "port", 70000, "type", "STANDALONE"))
                .when()
                .post()
                .then()
                .statusCode(400);
    }

    @Test
    void listsGetsUpdatesAndDeletes() {
        int id =
                given().contentType(ContentType.JSON)
                        .body(request("lifecycle"))
                        .when()
                        .post()
                        .then()
                        .statusCode(201)
                        .extract()
                        .path("id");

        given().when().get().then().statusCode(200).body("$", hasSize(1));
        given().when().get("/{id}", id).then().statusCode(200).body("name", equalTo("lifecycle"));

        // omitting the password must keep the stored one rather than clearing it
        given().contentType(ContentType.JSON)
                .body(
                        Map.of(
                                "name",
                                "lifecycle-renamed",
                                "host",
                                "127.0.0.1",
                                "port",
                                6380,
                                "tls",
                                false,
                                "database",
                                2,
                                "type",
                                "STANDALONE"))
                .when()
                .put("/{id}", id)
                .then()
                .statusCode(200)
                .body("name", equalTo("lifecycle-renamed"))
                .body("port", equalTo(6380))
                .body("database", equalTo(2))
                .body("hasPassword", is(true));

        given().when().delete("/{id}", id).then().statusCode(204);
        given().when().get("/{id}", id).then().statusCode(404);
    }

    @Test
    void clearsThePasswordWhenAnEmptyStringIsSent() {
        int id =
                given().contentType(ContentType.JSON)
                        .body(request("clear-secret"))
                        .when()
                        .post()
                        .then()
                        .extract()
                        .path("id");

        given().contentType(ContentType.JSON)
                .body(
                        Map.of(
                                "name", "clear-secret",
                                "host", "localhost",
                                "port", 6379,
                                "password", "",
                                "tls", false,
                                "database", 0,
                                "type", "STANDALONE"))
                .when()
                .put("/{id}", id)
                .then()
                .statusCode(200)
                .body("hasPassword", is(false));
    }

    @Test
    void returns404ForAnUnknownProfile() {
        given().when().get("/{id}", 999999).then().statusCode(404);
        given().when().delete("/{id}", 999999).then().statusCode(404);
    }

    @Test
    void keepsTheServerAProfileWasSavedWith() {
        int id =
                given().contentType(ContentType.JSON)
                        .body(request("valkey-target", Map.of("flavor", "VALKEY")))
                        .when()
                        .post()
                        .then()
                        .statusCode(201)
                        .body("flavor", equalTo("VALKEY"))
                        .extract()
                        .path("id");

        given().when().get("/{id}", id).then().statusCode(200).body("flavor", equalTo("VALKEY"));
    }

    @Test
    void aProfileThatDoesNotSayWhichServerWaitsForTheTargetToSay() {
        // Saying nothing is not the same as saying Redis: the catalog draws what the target
        // reported, and a profile nobody was asked about has nothing of its own to draw.
        given().contentType(ContentType.JSON)
                .body(request("unstated-target", Map.of()))
                .when()
                .post()
                .then()
                .statusCode(201)
                .body("flavor", equalTo("UNKNOWN"));
    }
}
