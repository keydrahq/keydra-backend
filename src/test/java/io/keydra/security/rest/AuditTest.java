package io.keydra.security.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

import io.keydra.connections.ConnectionFixtures;
import io.keydra.resources.RedisTargetsResource;
import io.keydra.security.AuditFixtures;
import io.keydra.security.Roles;
import io.keydra.security.SecuredProfile;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The audit log has to record what happened, including what failed. */
@QuarkusTest
@TestProfile(SecuredProfile.class)
@WithTestResource(RedisTargetsResource.class)
class AuditTest {

    private Long connectionId;

    @BeforeEach
    void setUp() {
        ConnectionFixtures.deleteAllProfiles();
        // The log is append-only in the application, so only a test ever clears it —
        // and without that, each case reads the previous cases' records as its own.
        AuditFixtures.deleteAllEvents();
        connectionId =
                ConnectionFixtures.createProfile(
                        "audited-target",
                        ConfigProvider.getConfig()
                                .getValue(RedisTargetsResource.REDIS_HOST, String.class),
                        ConfigProvider.getConfig()
                                .getValue(RedisTargetsResource.REDIS_PORT, Integer.class));
        RedisTargetsResource.flushRedis();
        RedisTargetsResource.execRedis("SET", "existing", "value");
    }

    @TestSecurity(user = "auditor", roles = Roles.ADMIN)
    private io.restassured.response.ValidatableResponse log() {
        return given().when().get("/api/v1/security/audit").then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "ada", roles = Roles.ADMIN)
    void recordsAMutationWithItsActorTargetAndOutcome() {
        given().contentType(ContentType.JSON)
                .body(Map.of("keys", List.of("existing")))
                .when()
                .post("/api/v1/connections/{id}/keys/delete", connectionId)
                .then()
                .statusCode(200);

        given().when()
                .get("/api/v1/security/audit?action=key.delete")
                .then()
                .statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].actor", equalTo("ada"))
                .body("[0].connectionId", equalTo(connectionId.intValue()))
                .body("[0].succeeded", is(true))
                .body("[0].at", org.hamcrest.Matchers.notNullValue());
    }

    @Test
    @TestSecurity(user = "ada", roles = Roles.ADMIN)
    void recordsAnAttemptThatChangedNothing() {
        // RENAMENX declines when the destination exists rather than failing, so the
        // operation succeeded and changed nothing. The log records that it was made.
        RedisTargetsResource.execRedis("SET", "taken", "value");
        given().contentType(ContentType.JSON)
                .body(Map.of("from", "existing", "to", "taken", "replace", false))
                .when()
                .post("/api/v1/connections/{id}/keys/rename", connectionId)
                .then()
                .statusCode(200)
                .body("affected", equalTo(0));

        given().when()
                .get("/api/v1/security/audit?action=key.rename")
                .then()
                .statusCode(200)
                .body("size()", equalTo(1));
    }

    @Test
    @TestSecurity(user = "ada", roles = Roles.ADMIN)
    void doesNotRecordReads() {
        // A log of every page view buries the entries somebody will come looking for.
        given().when()
                .get("/api/v1/connections/{id}/value?key=existing", connectionId)
                .then()
                .statusCode(200);
        given().when().get("/api/v1/connections").then().statusCode(200);

        given().when()
                .get("/api/v1/security/audit")
                .then()
                .statusCode(200)
                .body("size()", equalTo(0));
    }

    @Test
    @TestSecurity(user = "ada", roles = Roles.ADMIN)
    void neverRecordsTheValueThatWasWritten() {
        // A value written to a target may be a password; copying it into a queryable log
        // would turn an audit trail into a disclosure.
        given().contentType(ContentType.JSON)
                .body(Map.of("operation", "setString", "key", "secret", "value", "hunter2"))
                .when()
                .post("/api/v1/connections/{id}/value", connectionId)
                .then()
                .statusCode(200);

        String body =
                given().when()
                        .get("/api/v1/security/audit")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString();

        assertThat(body.contains("hunter2"), is(false));
    }

    @Test
    @TestSecurity(user = "ada", roles = Roles.ADMIN)
    void narrowsByActionAndByTarget() {
        given().contentType(ContentType.JSON)
                .body(Map.of("keys", List.of("existing")))
                .when()
                .post("/api/v1/connections/{id}/keys/delete", connectionId)
                .then()
                .statusCode(200);

        given().when()
                .get("/api/v1/security/audit?action=key.rename")
                .then()
                .statusCode(200)
                .body("size()", equalTo(0));

        given().when()
                .get("/api/v1/security/audit?connectionId={id}", connectionId)
                .then()
                .statusCode(200)
                .body("size()", equalTo(1));
    }

    @Test
    @TestSecurity(user = "ada", roles = Roles.ADMIN)
    void offersTheActionsItHasRecorded() {
        given().contentType(ContentType.JSON)
                .body(Map.of("keys", List.of("existing")))
                .when()
                .post("/api/v1/connections/{id}/keys/delete", connectionId)
                .then()
                .statusCode(200);

        given().when()
                .get("/api/v1/security/audit/actions")
                .then()
                .statusCode(200)
                .body("$", hasItem("key.delete"));
    }

    @Test
    @TestSecurity(user = "ada", roles = Roles.ADMIN)
    void recordsChangesToConnectionProfiles() {
        given().when().delete("/api/v1/connections/{id}", connectionId).then().statusCode(204);

        given().when()
                .get("/api/v1/security/audit?action=connection.delete")
                .then()
                .statusCode(200)
                .body("size()", equalTo(1))
                .body("[0].actor", equalTo("ada"));
    }
}
