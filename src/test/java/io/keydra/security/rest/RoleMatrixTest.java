package io.keydra.security.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

import io.keydra.connections.ConnectionFixtures;
import io.keydra.resources.RedisTargetsResource;
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

/**
 * The role matrix, exercised end to end.
 *
 * <p>Identities come from {@code @TestSecurity} rather than a real provider: what is under test is
 * which role may do what, not how a token is validated, and standing up Keycloak would add minutes
 * to every build to test somebody else's code.
 */
@QuarkusTest
@TestProfile(SecuredProfile.class)
@WithTestResource(RedisTargetsResource.class)
class RoleMatrixTest {

    private static Long connectionId;

    @BeforeEach
    void setUp() {
        ConnectionFixtures.deleteAllProfiles();
        // Inserted directly rather than over HTTP: creating a profile needs the admin role,
        // and the setup for a viewer's test — or an unauthenticated one — should not have to
        // be an admin first.
        connectionId =
                ConnectionFixtures.createProfile(
                        "role-matrix-target",
                        ConfigProvider.getConfig()
                                .getValue(RedisTargetsResource.REDIS_HOST, String.class),
                        ConfigProvider.getConfig()
                                .getValue(RedisTargetsResource.REDIS_PORT, Integer.class));
        RedisTargetsResource.flushRedis();
        RedisTargetsResource.execRedis("SET", "existing", "value");
    }

    // --- Anonymous ---------------------------------------------------------

    @Test
    void refusesAnUnauthenticatedRequest() {
        given().when().get("/api/v1/connections").then().statusCode(401);
    }

    @Test
    void letsAnyoneAskWhoTheyAre() {
        // A client has to be able to ask this before it knows whether to log in.
        given().when().get("/api/v1/security/me").then().statusCode(200);
    }

    @Test
    void leavesTheAboutPageOpen() {
        given().when().get("/api/v1/about").then().statusCode(200);
    }

    // --- Viewer ------------------------------------------------------------

    @Test
    @TestSecurity(user = "vera", roles = Roles.VIEWER)
    void aViewerReadsConnections() {
        given().when().get("/api/v1/connections").then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "vera", roles = Roles.VIEWER)
    void aViewerReadsValues() {
        given().when()
                .get("/api/v1/connections/{id}/value?key=existing", connectionId)
                .then()
                .statusCode(200);
    }

    @Test
    @TestSecurity(user = "vera", roles = Roles.VIEWER)
    void aViewerMayNotDeleteKeys() {
        given().contentType(ContentType.JSON)
                .body(Map.of("keys", List.of("existing")))
                .when()
                .post("/api/v1/connections/{id}/keys/delete", connectionId)
                .then()
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = "vera", roles = Roles.VIEWER)
    void aViewerMayNotWriteValues() {
        given().contentType(ContentType.JSON)
                .body(Map.of("operation", "setString", "key", "existing", "value", "changed"))
                .when()
                .post("/api/v1/connections/{id}/value", connectionId)
                .then()
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = "vera", roles = Roles.VIEWER)
    void aViewerHasNoConsole() {
        // The console can write, so reading its history is not a viewer's business either.
        given().when()
                .get("/api/v1/connections/{id}/console/history", connectionId)
                .then()
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = "vera", roles = Roles.VIEWER)
    void aViewerMayNotCreateConnections() {
        given().contentType(ContentType.JSON)
                .body(
                        Map.of(
                                "name",
                                "sneaky",
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
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = "vera", roles = Roles.VIEWER)
    void aViewerMayNotReadTheAuditLog() {
        given().when().get("/api/v1/security/audit").then().statusCode(403);
    }

    // --- Operator ----------------------------------------------------------

    @Test
    @TestSecurity(user = "oscar", roles = Roles.OPERATOR)
    void anOperatorWritesValues() {
        given().contentType(ContentType.JSON)
                .body(Map.of("operation", "setString", "key", "existing", "value", "changed"))
                .when()
                .post("/api/v1/connections/{id}/value", connectionId)
                .then()
                .statusCode(200);
    }

    @Test
    @TestSecurity(user = "oscar", roles = Roles.OPERATOR)
    void anOperatorDeletesKeys() {
        given().contentType(ContentType.JSON)
                .body(Map.of("keys", List.of("existing")))
                .when()
                .post("/api/v1/connections/{id}/keys/delete", connectionId)
                .then()
                .statusCode(200);
    }

    @Test
    @TestSecurity(user = "oscar", roles = Roles.OPERATOR)
    void anOperatorMayNotChangeConnections() {
        // Editing the data in a target is not the same right as pointing Keydra elsewhere.
        given().when().delete("/api/v1/connections/{id}", connectionId).then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "oscar", roles = Roles.OPERATOR)
    void anOperatorMayNotDisconnectSomebodyElsesClient() {
        given().when()
                .delete("/api/v1/connections/{id}/monitoring/clients/1", connectionId)
                .then()
                .statusCode(403);
    }

    @Test
    @TestSecurity(user = "oscar", roles = Roles.OPERATOR)
    void anOperatorMayNotManageAcls() {
        given().when().get("/api/v1/connections/{id}/acl", connectionId).then().statusCode(403);
    }

    // --- Admin -------------------------------------------------------------

    @Test
    @TestSecurity(user = "ada", roles = Roles.ADMIN)
    void anAdminManagesConnections() {
        given().when().delete("/api/v1/connections/{id}", connectionId).then().statusCode(204);
    }

    @Test
    @TestSecurity(user = "ada", roles = Roles.ADMIN)
    void anAdminReadsTheAuditLog() {
        given().when().get("/api/v1/security/audit").then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "ada", roles = Roles.ADMIN)
    void anAdminReadsTheTargetsUsers() {
        given().when()
                .get("/api/v1/connections/{id}/acl", connectionId)
                .then()
                .statusCode(200)
                // Redis always has a default user.
                .body("username", hasItem("default"));
    }

    @Test
    @TestSecurity(user = "ada", roles = Roles.ADMIN)
    void neverReturnsAPasswordHash() {
        // The server will give them out; they are useless to a UI and useful to an attacker.
        String body =
                given().when()
                        .get("/api/v1/connections/{id}/acl", connectionId)
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString();

        org.hamcrest.MatcherAssert.assertThat(body.contains("\"passwordHash\""), is(false));
        org.hamcrest.MatcherAssert.assertThat(body.matches("(?s).*\"#[0-9a-f]{64}\".*"), is(false));
    }

    @Test
    @TestSecurity(user = "ada", roles = Roles.ADMIN)
    void reportsWhoIsAskingAndWhatTheyHold() {
        given().when()
                .get("/api/v1/security/me")
                .then()
                .statusCode(200)
                .body("name", equalTo("ada"))
                .body("roles", hasItem(Roles.ADMIN))
                .body("securityEnabled", is(true));
    }

    // --- The hierarchy -----------------------------------------------------
    //
    // @RolesAllowed is an exact list, so "an admin is an operator" is true only because
    // every endpoint says so. These cases are what stops one endpoint being written
    // without saying it — a mistake that is invisible until someone with the right to do
    // something is told they have not.

    @Test
    @TestSecurity(user = "ada", roles = Roles.ADMIN)
    void anAdminCanDoWhatAnOperatorCan() {
        given().contentType(ContentType.JSON)
                .body(Map.of("keys", List.of("existing")))
                .when()
                .post("/api/v1/connections/{id}/keys/delete", connectionId)
                .then()
                .statusCode(200);
    }

    @Test
    @TestSecurity(user = "ada", roles = Roles.ADMIN)
    void anAdminCanDoWhatAViewerCan() {
        given().when().get("/api/v1/connections").then().statusCode(200);
        given().when()
                .get("/api/v1/connections/{id}/topology", connectionId)
                .then()
                .statusCode(200);
    }

    @Test
    @TestSecurity(user = "oscar", roles = Roles.OPERATOR)
    void anOperatorCanDoWhatAViewerCan() {
        given().when().get("/api/v1/connections").then().statusCode(200);
        given().when()
                .get("/api/v1/connections/{id}/value?key=existing", connectionId)
                .then()
                .statusCode(200);
    }

    @Test
    @TestSecurity(user = "ada", roles = Roles.ADMIN)
    void anAdminUsesTheConsole() {
        given().when()
                .get("/api/v1/connections/{id}/console/history", connectionId)
                .then()
                .statusCode(200);
    }

    @Test
    @TestSecurity(user = "oscar", roles = Roles.OPERATOR)
    void anOperatorUsesTheConsole() {
        given().when()
                .get("/api/v1/connections/{id}/console/history", connectionId)
                .then()
                .statusCode(200);
    }
}
