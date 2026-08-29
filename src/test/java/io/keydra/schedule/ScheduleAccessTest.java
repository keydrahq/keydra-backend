package io.keydra.schedule;

import static io.keydra.authz.AuthzFixtures.setUpAdministrator;
import static io.keydra.authz.AuthzFixtures.signIn;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import io.keydra.authz.AuthzFixtures;
import io.keydra.connections.ConnectionFixtures;
import io.keydra.resources.RedisTargetsResource;
import io.keydra.security.SecuredProfile;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A schedule is a way of doing something later, not a way of keeping access.
 *
 * <p>The sentence the whole feature rests on, and the one worth an end-to-end test: the permission
 * is asked when the schedule is written and again every time it runs. Without the second question,
 * arranging work would be a way of outliving the grant that allowed it — which is exactly the kind
 * of hole a feature adds quietly.
 */
@QuarkusTest
@TestProfile(SecuredProfile.class)
// The same resources as the other classes that enforce security, so the application is not
// restarted between them.
@WithTestResource(RedisTargetsResource.class)
class ScheduleAccessTest {

    private static final String PASSWORD = "deniz-has-a-long-password";

    private Long granted;
    private Long ungranted;
    private String adminSession;
    private String denizSession;
    private Integer grantId;

    @BeforeEach
    void buildTheGraph() {
        ScheduleFixtures.deleteEverySchedule();
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

        grantId =
                asAdmin()
                        .contentType(ContentType.JSON)
                        .body(
                                Map.of(
                                        "subjectType",
                                        "GROUP",
                                        "subjectId",
                                        team,
                                        "scopeType",
                                        "SERVER_GROUP",
                                        "scopeId",
                                        production,
                                        "roleId",
                                        roleNamed("operator")))
                        .when()
                        .post("/api/v1/authz/grants")
                        .then()
                        .statusCode(201)
                        .extract()
                        .path("id");

        denizSession = signIn("deniz", PASSWORD);
    }

    @Test
    void mayArrangeWorkOnTheServerTheyWereGrantedOn() {
        asDeniz()
                .contentType(ContentType.JSON)
                .body(request("Nightly flush", granted))
                .when()
                .post("/api/v1/schedules?connectionId=" + granted)
                .then()
                .statusCode(201);
    }

    @Test
    void mayNotArrangeWorkOnAServerNobodyGrantedThem() {
        asDeniz()
                .contentType(ContentType.JSON)
                .body(request("Somebody else's cache", ungranted))
                .when()
                .post("/api/v1/schedules?connectionId=" + ungranted)
                .then()
                .statusCode(403);
    }

    @Test
    void seesOnlyTheSchedulesOfServersTheyCanReach() {
        Integer mine = createAsDeniz("Mine", granted);
        createAsAdmin("Somebody else's", ungranted);

        asDeniz()
                .when()
                .get("/api/v1/schedules")
                .then()
                .statusCode(200)
                .body("", hasSize(1))
                .body("[0].id", equalTo(mine));
    }

    @Test
    void stopsRunningOnceItsAuthorNoLongerHoldsWhatItNeeds() {
        Integer schedule = createAsDeniz("Nightly flush", granted);

        // While the grant stands, the work happens.
        asAdmin()
                .when()
                .post("/api/v1/schedules/" + schedule + "/run?connectionId=" + granted)
                .then()
                .statusCode(200)
                .body("outcome", equalTo("DONE"));

        asAdmin().when().delete("/api/v1/authz/grants/" + grantId).then().statusCode(204);

        // Refused rather than failed: nothing went wrong, and the fix is a grant rather than an
        // investigation. The schedule is still there, and starts working again if it is
        // granted again — which is why this is an outcome and not a deletion.
        asAdmin()
                .when()
                .post("/api/v1/schedules/" + schedule + "/run?connectionId=" + granted)
                .then()
                .statusCode(200)
                .body("outcome", equalTo("REFUSED"))
                .body("detail", containsString("deniz"));

        asAdmin()
                .when()
                .get("/api/v1/schedules")
                .then()
                .statusCode(200)
                .body("[0].lastOutcome", equalTo("REFUSED"));
    }

    @Test
    void stopsRunningOnceItsAuthorIsTurnedOff() {
        Integer schedule = createAsDeniz("Nightly flush", granted);

        Integer deniz =
                asAdmin()
                        .when()
                        .get("/api/v1/authz/users")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("find { it.username == 'deniz' }.id");

        asAdmin()
                .contentType(ContentType.JSON)
                .body(Map.of("username", "deniz", "enabled", false))
                .when()
                .put("/api/v1/authz/users/" + deniz)
                .then()
                .statusCode(200);

        asAdmin()
                .when()
                .post("/api/v1/schedules/" + schedule + "/run?connectionId=" + granted)
                .then()
                .statusCode(200)
                .body("outcome", equalTo("REFUSED"));
    }

    /**
     * A copy that carries a script is two things, and the second is checked at every firing.
     *
     * <p>The permission to move keys is about this target; the permission to run a script is about
     * Keydra, because that is where the script runs. An operator holds the first and not the
     * second, which makes the two separable and this test worth having: arranging one is refused,
     * granting {@code script:run} allows it, and taking that grant away stops the schedule the next
     * time the clock reaches it — while the copy permission it still holds keeps standing.
     */
    @Test
    void aScriptOnAScheduleNeedsScriptRunWhenItIsWrittenAndAgainWhenItFires() {
        Map<String, Object> scripted = copyRequest("Nightly copy", granted, ungranted);

        // An operator may arrange the copy and may not arrange the script.
        asDeniz()
                .contentType(ContentType.JSON)
                .body(scripted)
                .when()
                .post("/api/v1/schedules?connectionId=" + granted)
                .then()
                .statusCode(409)
                .body("message", containsString("script:run"));

        Integer scripting =
                created(
                                "/api/v1/authz/roles",
                                Map.of(
                                        "name",
                                        "script-runner",
                                        "permissions",
                                        java.util.List.of("SCRIPT_RUN")))
                        .intValue();
        Integer scriptGrant =
                asAdmin()
                        .contentType(ContentType.JSON)
                        .body(
                                Map.of(
                                        "subjectType",
                                        "USER",
                                        "subjectId",
                                        denizId(),
                                        "scopeType",
                                        "INSTANCE",
                                        "roleId",
                                        scripting))
                        .when()
                        .post("/api/v1/authz/grants")
                        .then()
                        .statusCode(201)
                        .extract()
                        .path("id");

        Integer schedule =
                asDeniz()
                        .contentType(ContentType.JSON)
                        .body(scripted)
                        .when()
                        .post("/api/v1/schedules?connectionId=" + granted)
                        .then()
                        .statusCode(201)
                        .extract()
                        .path("id");

        asAdmin().when().delete("/api/v1/authz/grants/" + scriptGrant).then().statusCode(204);

        // The copy permission still stands, so the refusal names the one that does not — which is
        // the whole reason the guard reports a permission rather than a yes or a no.
        asAdmin()
                .when()
                .post("/api/v1/schedules/" + schedule + "/run?connectionId=" + granted)
                .then()
                .statusCode(200)
                .body("outcome", equalTo("REFUSED"))
                .body("detail", containsString("script:run"));
    }

    private Long denizId() {
        return asAdmin()
                .when()
                .get("/api/v1/authz/users")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("find { it.username == 'deniz' }.id");
    }

    private Map<String, Object> copyRequest(String name, Long from, Long to) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("connectionId", from);
        body.put("jobType", "COPY_KEYS");
        body.put("cron", "*/1 * * * *");
        body.put("enabled", true);
        body.put(
                "settings",
                "{\"targetConnectionId\":"
                        + to
                        + ",\"match\":\"absent:*\",\"script\":\"return true\"}");
        return body;
    }

    private Map<String, Object> request(String name, Long connectionId) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("connectionId", connectionId);
        body.put("jobType", "FLUSH_DATABASE");
        body.put("cron", "*/1 * * * *");
        body.put("enabled", true);
        body.put("settings", "{\"match\":\"absent:*\"}");
        return body;
    }

    private Integer createAsDeniz(String name, Long connectionId) {
        return create(asDeniz(), name, connectionId);
    }

    private Integer createAsAdmin(String name, Long connectionId) {
        return create(asAdmin(), name, connectionId);
    }

    private Integer create(RequestSpecification who, String name, Long connectionId) {
        return who.contentType(ContentType.JSON)
                .body(request(name, connectionId))
                .when()
                .post("/api/v1/schedules?connectionId=" + connectionId)
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    private RequestSpecification asAdmin() {
        return given().cookie("keydra_session", adminSession);
    }

    private RequestSpecification asDeniz() {
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
