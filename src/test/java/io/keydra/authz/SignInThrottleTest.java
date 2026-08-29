package io.keydra.authz;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;

import io.keydra.AbstractTestBase;
import io.keydra.security.SecuredProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * A password form that stops.
 *
 * <p>Unlimited attempts is two problems and only one of them is guessing. Argon2id at 19 MiB makes
 * each guess expensive for whoever is guessing and exactly as expensive for the server, so a few
 * hundred concurrent attempts is a memory bill nobody authenticated to run up. The limit is
 * therefore answered before the hash rather than after it.
 *
 * <p>Ordered, because these are one story rather than three tests: the window is rolling and shared
 * across the class, so what has already been tried is the state each of these is about.
 */
@QuarkusTest
@TestProfile(SecuredProfile.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SignInThrottleTest extends AbstractTestBase {

    private static final String NAME = "throttled-account";

    private static int attempt(String username, String password) {
        return given().contentType(ContentType.URLENC)
                .formParam("username", username)
                .formParam("password", password)
                .when()
                .post("/api/v1/auth/login")
                .then()
                .extract()
                .statusCode();
    }

    @Test
    @Order(1)
    void refusesTheAttemptAfterTheLimitWithoutCheckingThePassword() {
        // Eight is the configured allowance. Each of these is a real check that fails.
        for (int i = 1; i <= 8; i++) {
            org.junit.jupiter.api.Assertions.assertEquals(
                    401, attempt(NAME, "wrong" + i), "attempt " + i + " should be a refusal");
        }

        given().contentType(ContentType.URLENC)
                .formParam("username", NAME)
                .formParam("password", "wrong9")
                .when()
                .post("/api/v1/auth/login")
                .then()
                .statusCode(429)
                .header("Retry-After", notNullValue())
                .body(containsString("Too many sign-in attempts"));
    }

    @Test
    @Order(2)
    void saysNothingAboutWhetherTheAccountExists() {
        // The message names a wait and not a reason. Saying which limit was reached, or whether
        // there is an account behind the name, would answer for free the question the guessing is
        // being done to answer.
        given().contentType(ContentType.URLENC)
                .formParam("username", NAME)
                .formParam("password", "wrong10")
                .when()
                .post("/api/v1/auth/login")
                .then()
                .statusCode(429)
                .body(org.hamcrest.Matchers.not(containsString("account")))
                .body(org.hamcrest.Matchers.not(containsString("password")));
    }

    @Test
    @Order(3)
    void doesNotLetKnockingHoldSomebodyElseOut() {
        // A refusal must not count towards the limit that produced it. If it did, anybody could
        // hold a named account shut for as long as they cared to keep knocking — a limit against
        // guessing, turned into a way of stopping one person working. Another account on the same
        // network is unaffected by the ten attempts above.
        org.junit.jupiter.api.Assertions.assertEquals(401, attempt("somebody-else", "whatever"));
    }
}
