package io.keydra.authz;

import static io.keydra.authz.AuthzFixtures.setUpAdministrator;
import static io.keydra.authz.AuthzFixtures.signIn;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;

import io.keydra.authz.service.Totp;
import io.keydra.security.SecuredProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A second factor, from pairing it to being asked for it.
 *
 * <p>The claims worth an end-to-end test are the ones a unit test of the algorithm cannot make: a
 * secret that is never confirmed changes nothing, a confirmed one is asked for at the next sign-in,
 * a recovery code works once, and the password alone stops being enough.
 */
@QuarkusTest
@TestProfile(SecuredProfile.class)
class SecondFactorTest {

    private String session;

    @BeforeEach
    void anAccount() {
        AuthzFixtures.deleteEverythingButRoles();
        setUpAdministrator("ada");
        session = signIn("ada");
    }

    @Test
    void beginningAPairingChangesNothingUntilItIsProved() {
        String secret = begin();

        asAda().when().get("/api/v1/auth/second-factor").then().body("enabled", equalTo(false));

        // The whole reason confirmedAt exists: somebody who opens the page, scans nothing and
        // closes the tab has not locked themselves out.
        AuthzFixtures.signIn("ada");
        org.hamcrest.MatcherAssert.assertThat(secret, not(equalTo("")));
    }

    @Test
    void aProvedPairingTurnsItOnAndHandsOutRecoveryCodes() {
        String secret = begin();

        List<String> codes =
                asAda().contentType(ContentType.JSON)
                        .body(Map.of("code", Totp.codeAt(secret, Instant.now())))
                        .when()
                        .post("/api/v1/auth/second-factor/confirm")
                        .then()
                        .statusCode(200)
                        .extract()
                        .jsonPath()
                        .getList("codes");

        org.hamcrest.MatcherAssert.assertThat(codes, hasSize(10));
        asAda().when()
                .get("/api/v1/auth/second-factor")
                .then()
                .body("enabled", equalTo(true))
                .body("recoveryCodesLeft", equalTo(10));
    }

    @Test
    void theWrongCodeProvesNothing() {
        begin();

        asAda().contentType(ContentType.JSON)
                .body(Map.of("code", "000000"))
                .when()
                .post("/api/v1/auth/second-factor/confirm")
                .then()
                .statusCode(409);

        asAda().when().get("/api/v1/auth/second-factor").then().body("enabled", equalTo(false));
    }

    /** The point of the whole phase: the password stops being enough. */
    @Test
    void thePasswordAloneNoLongerSignsIn() {
        turnOn();

        given().contentType(ContentType.URLENC)
                .formParam("username", "ada")
                .formParam("password", AuthzFixtures.PASSWORD)
                .when()
                .post("/api/v1/auth/login")
                .then()
                .statusCode(401)
                .header("X-Keydra-Second-Factor", equalTo("required"));
    }

    @Test
    void thePasswordAndTheCodeTogetherDo() {
        String secret = turnOn();

        given().contentType(ContentType.URLENC)
                .formParam("username", "ada")
                .formParam("password", AuthzFixtures.PASSWORD)
                .formParam("code", Totp.codeAt(secret, Instant.now()))
                .when()
                .post("/api/v1/auth/login")
                .then()
                .statusCode(200);
    }

    /** A lost phone is the ordinary case, and a recovery code is what makes it survivable. */
    @Test
    void aRecoveryCodeWorksOnceAndThenDoesNot() {
        turnOn();
        String recovery = recoveryCodes().get(0);

        given().contentType(ContentType.URLENC)
                .formParam("username", "ada")
                .formParam("password", AuthzFixtures.PASSWORD)
                .formParam("code", recovery)
                .when()
                .post("/api/v1/auth/login")
                .then()
                .statusCode(200);

        given().contentType(ContentType.URLENC)
                .formParam("username", "ada")
                .formParam("password", AuthzFixtures.PASSWORD)
                .formParam("code", recovery)
                .when()
                .post("/api/v1/auth/login")
                .then()
                .statusCode(401);
    }

    @Test
    void turningItOffMakesThePasswordEnoughAgain() {
        turnOn();

        asAda().when().delete("/api/v1/auth/second-factor").then().statusCode(200);

        given().contentType(ContentType.URLENC)
                .formParam("username", "ada")
                .formParam("password", AuthzFixtures.PASSWORD)
                .when()
                .post("/api/v1/auth/login")
                .then()
                .statusCode(200);
    }

    private String begin() {
        return asAda().when()
                .post("/api/v1/auth/second-factor")
                .then()
                .statusCode(200)
                .extract()
                .path("secret");
    }

    /** Pairs and proves in one step, for the tests that are about what happens afterwards. */
    private String turnOn() {
        String secret = begin();
        asAda().contentType(ContentType.JSON)
                .body(Map.of("code", Totp.codeAt(secret, Instant.now())))
                .when()
                .post("/api/v1/auth/second-factor/confirm")
                .then()
                .statusCode(200);
        return secret;
    }

    private List<String> recoveryCodes() {
        return asAda().when()
                .post("/api/v1/auth/second-factor/recovery-codes")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("codes");
    }

    private RequestSpecification asAda() {
        return given().cookie("keydra_session", session);
    }
}
