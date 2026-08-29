package io.keydra.authz;

import static io.keydra.authz.LocalLoginTest.setUpAdministrator;
import static io.keydra.authz.LocalLoginTest.signIn;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import io.keydra.connections.ConnectionFixtures;
import io.keydra.resources.RedisTargetsResource;
import io.keydra.security.SecuredProfile;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What somebody with a grant on one server group can and cannot reach.
 *
 * <p>This is the test the phase exists for, and it is deliberately end to end. Every piece of the
 * model can be correct in isolation while the thing that applies it does nothing at all: the
 * interceptor shipped once with its binding members left binding, so CDI matched it against no
 * endpoint, every annotation in the application was inert, and the unit tests all passed. Only a
 * request that is actually refused proves otherwise.
 */
@QuarkusTest
@TestProfile(SecuredProfile.class)
// The same resources as the other classes that enforce security, so the application is not
// restarted between them — see the note on LocalLoginTest.
@WithTestResource(RedisTargetsResource.class)
class ScopedAccessTest {

    private static final String PASSWORD = "deniz-has-a-long-password";

    private Long granted;
    private Long ungranted;
    private String adminSession;
    private String denizSession;

    @BeforeEach
    void buildTheGraph() {
        ConnectionFixtures.deleteAllProfiles();
        AuthzFixtures.deleteEverythingButRoles();

        String host =
                ConfigProvider.getConfig().getValue(RedisTargetsResource.REDIS_HOST, String.class);
        int port =
                ConfigProvider.getConfig().getValue(RedisTargetsResource.REDIS_PORT, Integer.class);
        granted = ConnectionFixtures.createProfile("payments-cache", host, port);
        ungranted = ConnectionFixtures.createProfile("billing-cache", host, port);

        setUpAdministrator("ada");
        adminSession = signIn("ada");

        Long deniz =
                created(
                        "/api/v1/authz/users",
                        Map.of("username", "deniz", "password", PASSWORD, "enabled", true));
        Long team = created("/api/v1/authz/groups", Map.of("name", "payments-devs"));
        Long production = created("/api/v1/authz/server-groups", Map.of("name", "production"));
        Long viewer = roleNamed("viewer");

        asAdmin()
                .contentType(ContentType.JSON)
                .body(Map.of("userId", deniz))
                .when()
                .post("/api/v1/authz/groups/" + team + "/members")
                .then()
                .statusCode(204);

        asAdmin()
                .when()
                .post("/api/v1/authz/server-groups/" + production + "/servers/" + granted)
                .then()
                .statusCode(204);

        asAdmin()
                .contentType(ContentType.JSON)
                .body(
                        Map.of(
                                "subjectType", "GROUP",
                                "subjectId", team,
                                "scopeType", "SERVER_GROUP",
                                "scopeId", production,
                                "roleId", viewer))
                .when()
                .post("/api/v1/authz/grants")
                .then()
                .statusCode(201);

        denizSession = signIn("deniz", PASSWORD);
    }

    @Test
    void seesOnlyTheServerTheirGroupWasGrantedOn() {
        asDeniz()
                .when()
                .get("/api/v1/connections")
                .then()
                .statusCode(200)
                .body("", hasSize(1))
                .body("name", contains("payments-cache"));
    }

    @Test
    void isRefusedOnAServerNobodyGrantedThem() {
        // The regression test for an interceptor that was present and matched nothing.
        asDeniz()
                .when()
                .get("/api/v1/connections/" + ungranted + "/keys/tree")
                .then()
                .statusCode(403);
    }

    @Test
    void isRefusedAnythingAViewerMayNotDo() {
        asDeniz()
                .contentType(ContentType.JSON)
                .body(Map.of("keys", java.util.List.of("anything")))
                .when()
                .post("/api/v1/connections/" + granted + "/keys/delete")
                .then()
                .statusCode(403);
    }

    @Test
    void isRefusedTheAdministrationPagesEntirely() {
        asDeniz().when().get("/api/v1/authz/users").then().statusCode(403);
        asDeniz().when().get("/api/v1/authz/grants").then().statusCode(403);
        asDeniz().when().get("/api/v1/security/audit").then().statusCode(403);
    }

    @Test
    void isToldWhatTheyMayDoAndOnWhich() {
        asDeniz()
                .when()
                .get("/api/v1/auth/permissions")
                .then()
                .statusCode(200)
                .body("instance", is(empty()))
                .body("connections." + granted, org.hamcrest.Matchers.hasItem("KEYS_READ"))
                .body("connections." + ungranted, org.hamcrest.Matchers.nullValue());
    }

    @Test
    void losesItAsSoonAsTheGrantIsTakenBack() {
        Integer grantId =
                asAdmin()
                        .when()
                        .get("/api/v1/authz/grants")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("find { it.scopeType == 'SERVER_GROUP' }.id");

        asAdmin().when().delete("/api/v1/authz/grants/" + grantId).then().statusCode(204);

        // Refused rather than empty: somebody holding nothing anywhere is not somebody with an
        // empty list of servers, and Keydra's older role gate says so first. The session was
        // not re-issued, so this also proves the cookie carries a name rather than a copy of
        // what its owner could do at the moment they signed in — revoking takes effect now.
        asDeniz().when().get("/api/v1/connections").then().statusCode(403);
    }

    @Test
    void theAdministratorStillSeesEverything() {
        asAdmin().when().get("/api/v1/connections").then().statusCode(200).body("", hasSize(2));
    }

    private io.restassured.specification.RequestSpecification asAdmin() {
        return given().cookie("keydra_session", adminSession);
    }

    private io.restassured.specification.RequestSpecification asDeniz() {
        return given().cookie("keydra_session", denizSession);
    }

    private Long created(String path, Map<String, ?> body) {
        return asAdmin()
                .contentType(ContentType.JSON)
                .body(body)
                .when()
                .post(path)
                .then()
                .statusCode(201)
                .extract()
                .jsonPath()
                .getLong("id");
    }

    private Long roleNamed(String name) {
        return asAdmin()
                .when()
                .get("/api/v1/authz/roles")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("find { it.name == '" + name + "' }.id");
    }
}
