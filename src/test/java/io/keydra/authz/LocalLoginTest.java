package io.keydra.authz;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import io.keydra.resources.RedisTargetsResource;
import io.keydra.security.SecuredProfile;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Signing in with an account Keydra keeps itself.
 *
 * <p>Drives the whole path over HTTP rather than calling the pieces: form authentication, the
 * password check, the session cookie and the identity it restores are four mechanisms that only
 * mean anything together, and each of them has a way of being present and inert.
 */
@QuarkusTest
@TestProfile(SecuredProfile.class)
// The same resources as the other classes that enforce security. Not because this one talks
// to a target — it does not — but because Quarkus restarts the application whenever the set
// of test resources changes, and a restart in the middle of a run is where the container
// engine starts refusing to open any more of them.
@WithTestResource(RedisTargetsResource.class)
class LocalLoginTest {

    private static final String PASSWORD = AuthzFixtures.PASSWORD;

    @BeforeEach
    void emptyTheTables() {
        AuthzFixtures.deleteEverythingButRoles();
    }

    @Test
    void offersToCreateTheFirstAdministratorWhenThereIsNobody() {
        given().when()
                .get("/api/v1/auth/state")
                .then()
                .statusCode(200)
                .body("securityEnabled", is(true))
                .body("needsSetup", is(true))
                .body("authenticated", is(false));
    }

    @Test
    void createsTheFirstAdministratorAndThenRefusesToCreateAnother() {
        setUpAdministrator("ada");

        // The whole safety of an unauthenticated endpoint that creates an administrator is
        // that it can be reached once. If this ever returns 201, anybody can be one.
        given().contentType(ContentType.JSON)
                .body(Map.of("username", "mallory", "password", PASSWORD))
                .when()
                .post("/api/v1/auth/setup")
                .then()
                .statusCode(409);
    }

    @Test
    void saysThereIsNothingLeftToSetUpOnceThereIs() {
        setUpAdministrator("ada");

        given().when()
                .get("/api/v1/auth/state")
                .then()
                .statusCode(200)
                .body("needsSetup", is(false));
    }

    @Test
    void refusesTheWrongPassword() {
        setUpAdministrator("ada");

        given().formParam("username", "ada")
                .formParam("password", "not-the-password")
                .when()
                .post("/api/v1/auth/login")
                .then()
                .statusCode(401);
    }

    @Test
    void refusesAUsernameThatDoesNotExist() {
        setUpAdministrator("ada");

        given().formParam("username", "nobody")
                .formParam("password", PASSWORD)
                .when()
                .post("/api/v1/auth/login")
                .then()
                .statusCode(401);
    }

    @Test
    void acceptsTheRightPasswordAndIssuesASession() {
        setUpAdministrator("ada");

        String session = signIn("ada");

        given().cookie("keydra_session", session)
                .when()
                .get("/api/v1/auth/state")
                .then()
                .statusCode(200)
                .body("authenticated", is(true))
                .body("username", equalTo("ada"));
    }

    @Test
    void refusesEverythingWithoutOne() {
        setUpAdministrator("ada");

        given().when().get("/api/v1/connections").then().statusCode(401);
    }

    /**
     * Signing out clears the cookie.
     *
     * <p>Clears rather than revokes, and the difference is worth being precise about: the session
     * is the cookie — signed and encrypted, carrying a name and an expiry and nothing the server
     * keeps a record of. Logging out tells the browser to throw it away, and every browser does. A
     * copy taken out of one beforehand keeps working until it expires, which is what "stateless
     * session" means and what a server-side session store would be for.
     */
    @Test
    void clearsTheSessionCookieOnTheWayOut() {
        setUpAdministrator("ada");
        String session = signIn("ada");

        io.restassured.http.Cookie cleared =
                given().cookie("keydra_session", session)
                        .when()
                        .post("/api/v1/auth/logout")
                        .then()
                        .statusCode(204)
                        .extract()
                        .detailedCookie("keydra_session");

        assertThat(cleared.getValue(), is(""));
        assertThat(cleared.getMaxAge(), is(0L));
    }

    @Test
    void theFirstAdministratorHoldsEverythingOverTheInstance() {
        setUpAdministrator("ada");

        given().cookie("keydra_session", signIn("ada"))
                .when()
                .get("/api/v1/auth/permissions")
                .then()
                .statusCode(200)
                .body("instance", org.hamcrest.Matchers.hasItem("USERS_MANAGE"))
                .body("instance", org.hamcrest.Matchers.hasItem("GRANTS_MANAGE"));
    }

    @Test
    void neverSaysAnythingAboutTheStoredPassword() {
        setUpAdministrator("ada");

        String body =
                given().cookie("keydra_session", signIn("ada"))
                        .when()
                        .get("/api/v1/authz/users")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString();

        // Not the password, and not the hash either: a hash in an API response is a hash an
        // attacker can take away and work on at their leisure.
        org.hamcrest.MatcherAssert.assertThat(body, org.hamcrest.Matchers.not(containsAny()));
    }

    private static org.hamcrest.Matcher<String> containsAny() {
        return org.hamcrest.Matchers.anyOf(
                org.hamcrest.Matchers.containsString(PASSWORD),
                org.hamcrest.Matchers.containsString("argon2"),
                org.hamcrest.Matchers.containsString("passwordHash"));
    }

    // The helpers moved to AuthzFixtures once a test outside this package needed them; these
    // stay as the names this package already reads by.
    static void setUpAdministrator(String username) {
        AuthzFixtures.setUpAdministrator(username);
    }

    static String signIn(String username) {
        return AuthzFixtures.signIn(username);
    }

    static String signIn(String username, String password) {
        return AuthzFixtures.signIn(username, password);
    }
}
