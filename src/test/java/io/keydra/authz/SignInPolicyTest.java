package io.keydra.authz;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import io.keydra.authz.service.Totp;
import io.keydra.security.SecuredProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A second factor the instance asks for, rather than one somebody chose.
 *
 * <p>What is worth pinning is not that a boolean can be written. It is the guard that stops the
 * boolean from being a way to lose an installation, and the shape of what an account that owes a
 * factor can still reach — which is the difference between a wall somebody can get past and a
 * locked door.
 */
@QuarkusTest
@TestProfile(SecuredProfile.class)
class SignInPolicyTest {

    private String session;

    @BeforeEach
    void anAdministrator() {
        AuthzFixtures.deleteEverythingButRoles();
        AuthzFixtures.setUpAdministrator("ada");
        session = AuthzFixtures.signIn("ada");
    }

    /**
     * The one check that separates a switch from a way to lose an installation.
     *
     * <p>Without it, requiring a factor takes the roles away from the account doing the requiring —
     * including the permission to undo it — and the first person caught by the policy is the person
     * who set it.
     */
    @Test
    void nobodyMayRequireAFactorTheyDoNotHave() {
        asAda().contentType(ContentType.JSON)
                .body(Map.of("secondFactorRequired", true))
                .when()
                .put("/api/v1/auth/policy")
                .then()
                .statusCode(409)
                // Plain text rather than a problem document: that is what this mapper has always
                // answered with, and the sentence is the whole of what the page shows.
                .body(containsString("lock you out"));

        asAda().when()
                .get("/api/v1/auth/policy")
                .then()
                .statusCode(200)
                .body("secondFactorRequired", is(false));
    }

    @Test
    void anAdministratorWhoHasOneMayRequireOne() {
        pairAndProve();

        asAda().contentType(ContentType.JSON)
                .body(Map.of("secondFactorRequired", true))
                .when()
                .put("/api/v1/auth/policy")
                .then()
                .statusCode(200)
                .body("secondFactorRequired", is(true));

        asAda().when()
                .get("/api/v1/auth/policy")
                .then()
                .body("secondFactorRequired", is(true))
                .body("changedBy", equalTo("ada"))
                // Every account this reaches now has one, which is the reading that says the
                // requirement is costing nobody anything.
                .body("accountsOwingAFactor", equalTo(0));
    }

    /** The number to know before pressing the switch: how many people it shuts out this morning. */
    @Test
    void theCountIsOfTheAccountsTheRequirementWouldCatch() {
        AuthzFixtures.createUserWithPassword(session, "bob");

        asAda().when().get("/api/v1/auth/policy").then().body("accountsOwingAFactor", equalTo(2));

        pairAndProve();

        asAda().when().get("/api/v1/auth/policy").then().body("accountsOwingAFactor", equalTo(1));
    }

    /**
     * The centre of the phase, walked the way somebody moving to a new phone walks it.
     *
     * <p>Ada requires a factor while she has one, then turns her own off — which is allowed, and is
     * how a phone gets replaced. From that moment she reaches nothing that needs a role, and
     * everything that is about her own account still answers. Pairing again gives it all back on
     * the next request, because the cache is cleared by the confirmation rather than by waiting.
     */
    @Test
    void anAccountThatOwesAFactorReachesNothingButItsOwnEnrolment() {
        pairAndProve();
        require(true);

        // Still hers to turn off. Refusing this would make replacing a phone something you have
        // to ask somebody for.
        asAda().when().delete("/api/v1/auth/second-factor").then().statusCode(200);

        asAda().when().get("/api/v1/authz/users").then().statusCode(403);
        asAda().when().get("/api/v1/security/audit").then().statusCode(403);

        // And the browser is told why, rather than being left to read a wall of 403s.
        asAda().contentType(ContentType.JSON)
                .body(Map.of("query", "{ authState { authenticated mustEnrolSecondFactor } }"))
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("data.authState.authenticated", is(true))
                .body("data.authState.mustEnrolSecondFactor", is(true));

        // What stays open is what was already open to somebody holding no grants at all: your own
        // factor, your own sessions, your own preferences.
        asAda().when().get("/api/v1/auth/second-factor").then().statusCode(200);
        asAda().when().get("/api/v1/auth/sessions").then().statusCode(200);
        asAda().when().get("/api/v1/preferences").then().statusCode(200);

        pairAndProve();

        asAda().when().get("/api/v1/authz/users").then().statusCode(200);
    }

    /**
     * Which is why the guard exists, said from the other side.
     *
     * <p>An account the requirement has caught cannot undo the requirement. That is not a bug to
     * work around — it is the reason nobody may turn this on without a factor, so that there is
     * always an administrator who can turn it off.
     */
    @Test
    void anAccountTheRequirementHasCaughtCannotUndoIt() {
        pairAndProve();
        require(true);
        asAda().when().delete("/api/v1/auth/second-factor").then().statusCode(200);

        asAda().contentType(ContentType.JSON)
                .body(Map.of("secondFactorRequired", false))
                .when()
                .put("/api/v1/auth/policy")
                .then()
                .statusCode(403);
    }

    @Test
    void turningItOffGivesAccessBackWithoutAnybodyEnrolling() {
        pairAndProve();
        require(true);
        AuthzFixtures.createUserWithPassword(session, "bob");

        // Bob has no factor and no grants, so what says the policy let go of him is the answer to
        // the one question every page waits on.
        String bobs = AuthzFixtures.signIn("bob");
        given().cookie("keydra_session", bobs)
                .contentType(ContentType.JSON)
                .body(Map.of("query", "{ authState { mustEnrolSecondFactor } }"))
                .when()
                .post("/graphql")
                .then()
                .body("data.authState.mustEnrolSecondFactor", is(true));

        require(false);

        given().cookie("keydra_session", bobs)
                .contentType(ContentType.JSON)
                .body(Map.of("query", "{ authState { mustEnrolSecondFactor } }"))
                .when()
                .post("/graphql")
                .then()
                .body("data.authState.mustEnrolSecondFactor", is(false));
    }

    @Test
    void settingTheTermsEverybodySignsInUnderIsItsOwnPermission() {
        AuthzFixtures.createUserWithPassword(session, "bob");
        String bobs = AuthzFixtures.signIn("bob");

        given().cookie("keydra_session", bobs)
                .when()
                .get("/api/v1/auth/policy")
                .then()
                .statusCode(403);
    }

    // --- Helpers -----------------------------------------------------------

    /**
     * Pairs an authenticator with Ada's account and proves it, which is two requests every time.
     */
    private void pairAndProve() {
        String secret =
                asAda().when()
                        .post("/api/v1/auth/second-factor")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("secret");
        asAda().contentType(ContentType.JSON)
                .body(Map.of("code", Totp.codeAt(secret, Instant.now())))
                .when()
                .post("/api/v1/auth/second-factor/confirm")
                .then()
                .statusCode(200);
    }

    private void require(boolean required) {
        asAda().contentType(ContentType.JSON)
                .body(Map.of("secondFactorRequired", required))
                .when()
                .put("/api/v1/auth/policy")
                .then()
                .statusCode(200);
    }

    private RequestSpecification asAda() {
        return given().cookie("keydra_session", session);
    }
}
