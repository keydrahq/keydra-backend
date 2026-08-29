package io.keydra.approvals.rest;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.startsWith;

import io.keydra.approvals.ApprovalFixtures;
import io.keydra.authz.AuthzFixtures;
import io.keydra.connections.ConnectionFixtures;
import io.keydra.resources.RedisTargetsResource;
import io.keydra.schedule.ScheduleFixtures;
import io.keydra.security.SecuredProfile;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A target that nobody empties on their own.
 *
 * <p>End to end, and deliberately, for the reason {@code ScopedAccessTest} is: every piece of this
 * can be right in isolation while the thing that applies it does nothing. A guard that is asked in
 * the service rather than on the endpoint is a guard whose absence no annotation test can see, and
 * the only proof it is there is a request that is genuinely recorded instead of carried out.
 *
 * <p>Three people, which is the smallest cast the feature has: somebody who asks, somebody who
 * could have asked and can therefore answer, and somebody who is unmistakably signed in and has no
 * standing on this server at all.
 */
@QuarkusTest
@TestProfile(SecuredProfile.class)
// The same resources as the other classes that enforce security, so the application is not
// restarted between them.
@WithTestResource(RedisTargetsResource.class)
class ApprovalTest {

    private Long guarded;
    private Long ordinary;
    private String adminSession;
    private String denizSession;
    private String eceSession;
    private String vuralSession;

    @BeforeEach
    void setUp() {
        ApprovalFixtures.deleteEveryRequest();
        ScheduleFixtures.deleteEverySchedule();
        ConnectionFixtures.deleteAllProfiles();
        AuthzFixtures.deleteEverythingButRoles();
        RedisTargetsResource.flushRedis();

        String host =
                ConfigProvider.getConfig().getValue(RedisTargetsResource.REDIS_HOST, String.class);
        int port =
                ConfigProvider.getConfig().getValue(RedisTargetsResource.REDIS_PORT, Integer.class);

        guarded = ConnectionFixtures.createProfile("payments-prod", host, port);
        ordinary = ConnectionFixtures.createProfile("payments-cache", host, port);
        ConnectionFixtures.requireApproval(guarded);

        AuthzFixtures.setUpAdministrator("ada");
        adminSession = AuthzFixtures.signIn("ada");

        // Deniz asks, Ece answers: both operators on the guarded target, which is the whole rule
        // about who may agree — somebody who could have done it alone.
        denizSession = operatorOn("deniz", List.of(guarded, ordinary));
        eceSession = operatorOn("ece", List.of(guarded));
        // And somebody with the same role somewhere else entirely, so a refusal here is about
        // this target rather than about not being signed in.
        vuralSession = operatorOn("vural", List.of(ordinary));
    }

    @Test
    void aPurgeIsRecordedRatherThanPerformed() {
        RedisTargetsResource.execRedis("SET", "doomed:1", "value");

        asDeniz()
                .contentType(ContentType.JSON)
                .body(Map.of("match", "doomed:*"))
                .when()
                .post("/api/v1/connections/" + guarded + "/keys/purge")
                .then()
                // Not 200. Something calling this from a script must be able to tell "recorded"
                // from "done", and not an error either: nothing was refused and nothing was
                // malformed.
                .statusCode(202)
                .body("awaitingApproval", is(true))
                .body("kind", equalTo("PURGE_KEYS"))
                .body("connectionName", equalTo("payments-prod"))
                .body("id", notNullValue());

        // The key that would have gone is still there, which is the assertion the status code is
        // only a claim about.
        org.junit.jupiter.api.Assertions.assertEquals(
                "1", RedisTargetsResource.execRedis("EXISTS", "doomed:1").trim());

        asDeniz()
                .when()
                .get("/api/v1/approvals")
                .then()
                .statusCode(200)
                .body("", hasSize(1))
                .body("[0].state", equalTo("PENDING"))
                .body("[0].summary", containsString("doomed:*"))
                .body("[0].requestedBy", equalTo("deniz"));
    }

    @Test
    void nobodyApprovesTheirOwnRequest() {
        long request = raisePurge("doomed:*");

        asDeniz()
                .when()
                .get("/api/v1/approvals")
                .then()
                .body("[0].mine", is(true))
                // The reason there are no buttons, said in the row rather than left to be worked
                // out from an absence.
                .body("[0].canDecide", is(false));

        asDeniz()
                .when()
                .post("/api/v1/approvals/" + request + "/approve")
                .then()
                .statusCode(409)
                .body("message", containsString("Nobody approves their own request"));
    }

    @Test
    void somebodyElseWhoCouldHaveDoneItApproves() {
        RedisTargetsResource.execRedis("SET", "doomed:1", "value");
        long request = raisePurge("doomed:*");

        asEce().when()
                .get("/api/v1/approvals")
                .then()
                .body("[0].mine", is(false))
                .body("[0].canDecide", is(true));

        asEce().when()
                .post("/api/v1/approvals/" + request + "/approve")
                .then()
                .statusCode(200)
                .body("decidedBy", equalTo("ece"));

        // Answered as soon as the work is under way, so what it did arrives afterwards — and what
        // it did is the point: the operation stored is the operation that ran.
        awaitState(request, "DONE");
        org.junit.jupiter.api.Assertions.assertEquals(
                "0", RedisTargetsResource.execRedis("EXISTS", "doomed:1").trim());
    }

    @Test
    void somebodyWithNoStandingOnThisTargetCannotAnswerAndCannotSee() {
        long request = raisePurge("doomed:*");

        // Signed in, holds operator on another server, and this is none of their business — the
        // list is filtered by what they can see rather than by a permission of its own.
        asVural().when().get("/api/v1/approvals").then().statusCode(200).body("", hasSize(0));

        asVural()
                .when()
                .post("/api/v1/approvals/" + request + "/approve")
                .then()
                .statusCode(409)
                .body("message", containsString("keys:delete"));
    }

    @Test
    void decliningSaysWhyAndLeavesTheKeyspaceAlone() {
        RedisTargetsResource.execRedis("SET", "doomed:1", "value");
        long request = raisePurge("doomed:*");

        asEce().contentType(ContentType.JSON)
                .body(Map.of("reason", "Not during the sale"))
                .when()
                .post("/api/v1/approvals/" + request + "/decline")
                .then()
                .statusCode(200)
                .body("state", equalTo("DECLINED"))
                .body("detail", equalTo("Not during the sale"));

        org.junit.jupiter.api.Assertions.assertEquals(
                "1", RedisTargetsResource.execRedis("EXISTS", "doomed:1").trim());

        // And it stays answered. A second person pressing approve a moment later is the race the
        // state transition exists to lose.
        asEce().when()
                .post("/api/v1/approvals/" + request + "/approve")
                .then()
                .statusCode(409)
                .body("message", containsString("already been answered"));
    }

    @Test
    void onlyThePersonWhoAskedCanWithdraw() {
        long request = raisePurge("doomed:*");

        asEce().when().delete("/api/v1/approvals/" + request).then().statusCode(409);

        asDeniz()
                .when()
                .delete("/api/v1/approvals/" + request)
                .then()
                .statusCode(200)
                .body("state", equalTo("WITHDRAWN"));
    }

    @Test
    void aTargetThatDoesNotAskIsUnchanged() {
        RedisTargetsResource.execRedis("SET", "spare:1", "value");

        asDeniz()
                .contentType(ContentType.JSON)
                .body(Map.of("match", "spare:*"))
                .when()
                .post("/api/v1/connections/" + ordinary + "/keys/purge")
                .then()
                .statusCode(200)
                .body("affected", equalTo(1));

        // Nothing was recorded, because nothing was asked. The flag is off by default and every
        // automation that has ever called this endpoint still works.
        asDeniz().when().get("/api/v1/approvals").then().body("", hasSize(0));
    }

    @Test
    void whatTheOperationWouldDoIsNotReadableInTheDatabase() {
        raisePurge("customer:*:card");

        String stored = ConnectionFixtures.rawColumn("approval_request", "payload");
        // Not a credential, and encrypted for a different reason: a glob and a list of key names
        // are the contents of somebody's target, and this row sits there until a colleague gets
        // back from lunch.
        org.junit.jupiter.api.Assertions.assertNotNull(stored);
        org.hamcrest.MatcherAssert.assertThat(stored, startsWith("enc:"));
        org.junit.jupiter.api.Assertions.assertFalse(stored.contains("customer"));
    }

    @Test
    void aRequestNobodyAnswersExpiresRatherThanWaitingForEver() {
        long request = raisePurge("doomed:*");

        ApprovalFixtures.backdate(request);
        org.junit.jupiter.api.Assertions.assertEquals(1, ApprovalFixtures.expireNow());

        asDeniz()
                .when()
                .get("/api/v1/approvals?all=true")
                .then()
                .body("[0].state", equalTo("EXPIRED"))
                // An ending rather than a deletion: the failure this prevents is somebody
                // believing an operation is arranged when it is never going to happen.
                .body("[0].detail", containsString("Nobody answered"));

        asEce().when().post("/api/v1/approvals/" + request + "/approve").then().statusCode(409);
    }

    @Test
    void anApprovalDoesNothingOnceTheRequesterHasLostTheirAccess() {
        RedisTargetsResource.execRedis("SET", "doomed:1", "value");
        long request = raisePurge("doomed:*");

        revokeEveryGrantFor("deniz");

        asEce().when().post("/api/v1/approvals/" + request + "/approve").then().statusCode(200);

        // The gap between asking and agreeing is hours by design, and without this it would be
        // somewhere an access that has been taken away still works.
        awaitState(request, "FAILED");
        asEce().when()
                .get("/api/v1/approvals?all=true")
                .then()
                .body("[0].detail", containsString("keys:delete"));
        org.junit.jupiter.api.Assertions.assertEquals(
                "1", RedisTargetsResource.execRedis("EXISTS", "doomed:1").trim());
    }

    @Test
    void arrangingOneForLaterIsAskingForOne() {
        Map<String, Object> schedule =
                Map.of(
                        "name", "nightly-flush",
                        "connectionId", guarded,
                        "jobType", "FLUSH_DATABASE",
                        "cron", "0 3 * * *",
                        "enabled", true,
                        "settings", "{\"match\":\"doomed:*\"}",
                        "confirmTarget", "payments-prod");

        asDeniz()
                .contentType(ContentType.JSON)
                .body(schedule)
                .when()
                // The coarse gate reads the target beside the request, which is what phase 59
                // left it doing on purpose: the decision that matters is made in the service,
                // against the connection the job will actually run against.
                .post("/api/v1/schedules?connectionId=" + guarded)
                .then()
                // Otherwise somebody who may not purge this target writes a job that purges it in
                // two minutes and is asked nothing.
                .statusCode(202)
                .body("kind", equalTo("SCHEDULE_WRITE"));

        // Nothing exists in the meantime — not a disabled row, not a draft.
        asDeniz().when().get("/api/v1/schedules").then().body("", hasSize(0));

        long request = onlyRequest();
        asEce().when().post("/api/v1/approvals/" + request + "/approve").then().statusCode(200);
        awaitState(request, "DONE");

        asDeniz()
                .when()
                .get("/api/v1/schedules")
                .then()
                .body("", hasSize(1))
                .body("[0].name", equalTo("nightly-flush"))
                // Whoever asked, rather than whoever agreed: a schedule runs with its author's
                // access, and agreeing to somebody's arrangement is not making one.
                .body("[0].createdBy", equalTo("deniz"));
    }

    @Test
    void theSecondSurfaceSaysSoInACodeRatherThanInAStatus() {
        String mutation =
                "mutation { purgeKeys(connectionId: "
                        + guarded
                        + ", purge: { match: \"doomed:*\" }) { affected } }";

        asDeniz()
                .contentType(ContentType.JSON)
                .body(Map.of("query", mutation))
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                // Every answer here is 200, so the wording is all a browser would otherwise have
                // to go on — and wording is one translation away from breaking.
                .body("errors[0].extensions.code", equalTo("approval-required"))
                .body("errors[0].message", containsString("nobody empties on their own"))
                .body("data.purgeKeys", org.hamcrest.Matchers.nullValue());

        asDeniz().when().get("/api/v1/approvals").then().body("", hasSize(1));
    }

    @Test
    void askingForTwoPeopleIsSomethingTheProfileSays() {
        String host =
                ConfigProvider.getConfig().getValue(RedisTargetsResource.REDIS_HOST, String.class);
        int port =
                ConfigProvider.getConfig().getValue(RedisTargetsResource.REDIS_PORT, Integer.class);

        Integer id =
                asAdmin()
                        .contentType(ContentType.JSON)
                        .body(
                                Map.of(
                                        "name",
                                        "orders-prod",
                                        "host",
                                        host,
                                        "port",
                                        port,
                                        "tls",
                                        false,
                                        "database",
                                        0,
                                        "type",
                                        "STANDALONE",
                                        "requiresApproval",
                                        true))
                        .when()
                        .post("/api/v1/connections")
                        .then()
                        .statusCode(201)
                        .body("requiresApproval", is(true))
                        // Beside the naming rather than inside it: one asks which server this is
                        // and the other asks whether it should happen at all.
                        .body("guarded", is(false))
                        .extract()
                        .path("id");

        asAdmin().when().get("/api/v1/connections/" + id).then().body("requiresApproval", is(true));
    }

    // --- helpers ---------------------------------------------------------

    private long raisePurge(String match) {
        asDeniz()
                .contentType(ContentType.JSON)
                .body(Map.of("match", match))
                .when()
                .post("/api/v1/connections/" + guarded + "/keys/purge")
                .then()
                .statusCode(202);
        return onlyRequest();
    }

    /** The one request there is, read as the person who raised it. */
    private long onlyRequest() {
        return asDeniz()
                .when()
                .get("/api/v1/approvals?all=true")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getLong("[0].id");
    }

    /**
     * Waits for the work to end.
     *
     * <p>Approving answers as soon as the work is under way rather than when it finishes — a purge
     * of a large keyspace is a minute or ten, and a colleague pressing approve should not be
     * holding an HTTP request open for it.
     */
    private void awaitState(long request, String state) {
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                asEce().when()
                                        .get("/api/v1/approvals/" + request)
                                        .then()
                                        .body("state", equalTo(state)));
    }

    /** An account with the operator role on each of these targets, signed in. */
    private String operatorOn(String username, List<Long> targets) {
        AuthzFixtures.createUserWithPassword(adminSession, username);
        Long user =
                asAdmin()
                        .when()
                        .get("/api/v1/authz/users")
                        .then()
                        .statusCode(200)
                        .extract()
                        .jsonPath()
                        .getLong("find { it.username == '" + username + "' }.id");
        Long operator =
                asAdmin()
                        .when()
                        .get("/api/v1/authz/roles")
                        .then()
                        .statusCode(200)
                        .extract()
                        .jsonPath()
                        .getLong("find { it.name == 'operator' }.id");

        for (Long target : targets) {
            asAdmin()
                    .contentType(ContentType.JSON)
                    .body(
                            Map.of(
                                    "subjectType", "USER",
                                    "subjectId", user,
                                    "scopeType", "CONNECTION",
                                    "scopeId", target,
                                    "roleId", operator))
                    .when()
                    .post("/api/v1/authz/grants")
                    .then()
                    .statusCode(201);
        }
        return AuthzFixtures.signIn(username);
    }

    private void revokeEveryGrantFor(String username) {
        List<Integer> grants =
                asAdmin()
                        .when()
                        .get("/api/v1/authz/grants")
                        .then()
                        .statusCode(200)
                        .extract()
                        .jsonPath()
                        .getList("findAll { it.subjectName == '" + username + "' }.id");
        for (Integer grant : grants) {
            asAdmin().when().delete("/api/v1/authz/grants/" + grant).then().statusCode(204);
        }
    }

    private RequestSpecification asAdmin() {
        return given().cookie("keydra_session", adminSession);
    }

    private RequestSpecification asDeniz() {
        return given().cookie("keydra_session", denizSession);
    }

    private RequestSpecification asEce() {
        return given().cookie("keydra_session", eceSession);
    }

    private RequestSpecification asVural() {
        return given().cookie("keydra_session", vuralSession);
    }
}
