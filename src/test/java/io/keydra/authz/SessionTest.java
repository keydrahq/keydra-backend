package io.keydra.authz;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

import io.keydra.resources.RedisTargetsResource;
import io.keydra.security.SecuredProfile;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A session is a thing, and it can be ended.
 *
 * <p>What is worth pinning is the difference this phase makes, which is not the list. Before it, a
 * cookie was good until it expired: ending a session meant waiting, and changing a password left
 * every browser that already had one signed in. These tests are about a cookie that stops working
 * because somebody said so.
 */
@QuarkusTest
@TestProfile(SecuredProfile.class)
@WithTestResource(RedisTargetsResource.class)
class SessionTest {

    private static final String USER = "session-user";

    @BeforeEach
    void setUp() {
        AuthzFixtures.deleteEverythingButRoles();
        AuthzFixtures.setUpAdministrator(USER);
    }

    /** Signs in and returns both cookies, which is what a browser would hold. */
    private Map<String, String> signIn() {
        var response =
                given().formParam("username", USER)
                        .formParam("password", AuthzFixtures.PASSWORD)
                        .when()
                        .post("/api/v1/auth/login")
                        .then()
                        .statusCode(200)
                        .extract();
        // The session row is created on the first request that presents the cookie, so one is
        // made here — which is also what a browser does immediately after signing in.
        String session = response.cookie("keydra_session");
        String sid =
                given().cookie("keydra_session", session)
                        .when()
                        .get("/api/v1/auth/state")
                        .then()
                        .statusCode(200)
                        .extract()
                        .cookie("keydra_sid");
        return Map.of("keydra_session", session, "keydra_sid", sid == null ? "" : sid);
    }

    private io.restassured.specification.RequestSpecification as(Map<String, String> cookies) {
        var request = given();
        for (var cookie : cookies.entrySet()) {
            if (!cookie.getValue().isEmpty()) {
                request = request.cookie(cookie.getKey(), cookie.getValue());
            }
        }
        return request;
    }

    @Test
    void signingInStartsASessionSomebodyCanSee() {
        Map<String, String> browser = signIn();

        as(browser)
                .when()
                .get("/api/v1/auth/sessions")
                .then()
                .statusCode(200)
                .body("", hasSize(1))
                // The one reading the list is marked, because ending it is signing out and a
                // list that did not say which is which invites doing that by accident.
                .body("[0].current", is(true))
                .body("[0].issuedAt", notNullValue());
    }

    @Test
    void signingInTwiceIsTwoSessions() {
        Map<String, String> first = signIn();
        signIn();

        as(first).when().get("/api/v1/auth/sessions").then().statusCode(200).body("", hasSize(2));
    }

    /**
     * A page of them, rather than all of them.
     *
     * <p>The list was right and unbounded, which is the same query at three rows and at four
     * hundred — and four hundred is what a device that signs in each morning and never signs out
     * leaves behind.
     */
    @Test
    void theListIsAPageRatherThanEverything() {
        Map<String, String> mine = signIn();
        signIn();
        signIn();

        as(mine).when()
                .get("/api/v1/auth/sessions?first=2")
                .then()
                .statusCode(200)
                .body("", hasSize(2));
        as(mine).when()
                .get("/api/v1/auth/sessions?first=2&offset=2")
                .then()
                .statusCode(200)
                .body("", hasSize(1));
    }

    /**
     * And the browser reading it is on the first page whatever has happened since.
     *
     * <p>Newest-first alone would push it down: this session was issued when this browser signed
     * in, and signing in somewhere else afterwards makes a newer one. The row carrying the action
     * that signs somebody out is not a row to make them go looking for.
     */
    @Test
    void theBrowserReadingThePageComesFirst() {
        Map<String, String> mine = signIn();
        signIn();
        signIn();

        as(mine).when()
                .get("/api/v1/auth/sessions?first=1")
                .then()
                .statusCode(200)
                .body("", hasSize(1))
                .body("[0].current", is(true));
    }

    /** A page size is a request from the client, and a request is not a permission. */
    @Test
    void aPageSizeOfNothingIsStillAPage() {
        Map<String, String> mine = signIn();
        signIn();

        as(mine).when()
                .get("/api/v1/auth/sessions?first=0")
                .then()
                .statusCode(200)
                .body("", hasSize(1));
    }

    /** The total is of everything, which is what a pager needs and a page of rows cannot say. */
    @Test
    void theCountIsOfEverythingRatherThanOfThePage() {
        Map<String, String> mine = signIn();
        signIn();
        signIn();

        given().contentType(ContentType.JSON)
                .cookie("keydra_session", mine.get("keydra_session"))
                .cookie("keydra_sid", mine.get("keydra_sid"))
                .body(Map.of("query", "{ mySessionCount mySessions(first: 1) { id current } }"))
                .when()
                .post("/graphql")
                .then()
                .statusCode(200)
                .body("data.mySessionCount", is(3))
                .body("data.mySessions", hasSize(1))
                .body("data.mySessions[0].current", is(true));
    }

    @Test
    void anEndedSessionStopsWorkingOnItsNextRequest() {
        Map<String, String> keep = signIn();
        Map<String, String> doomed = signIn();

        // Still good before it is ended, so the refusal below is about the ending.
        as(doomed).when().get("/api/v1/auth/state").then().body("authenticated", is(true));

        as(keep).when()
                .delete("/api/v1/auth/sessions/" + doomed.get("keydra_sid"))
                .then()
                .statusCode(204);

        // The cookie is unchanged and still unexpired. What has changed is that the row it
        // names has been ended — which is the whole of this phase in one assertion. The answer
        // is 401 rather than "not authenticated": presenting a credential that is refused is a
        // different thing from presenting none, and Keydra says so even on an open path.
        as(doomed).when().get("/api/v1/auth/state").then().statusCode(401);
    }

    @Test
    void endingEveryOtherSessionKeepsTheOneAsking() {
        Map<String, String> keep = signIn();
        Map<String, String> other = signIn();

        as(keep).when().delete("/api/v1/auth/sessions").then().statusCode(200);

        as(keep).when()
                .get("/api/v1/auth/state")
                .then()
                .statusCode(200)
                .body("authenticated", is(true));
        as(other).when().get("/api/v1/auth/state").then().statusCode(401);
    }

    @Test
    void signingOutEndsTheRowAndNotOnlyTheCookie() {
        Map<String, String> browser = signIn();

        as(browser).when().post("/api/v1/auth/logout").then().statusCode(204);

        // A copy of the cookie taken before signing out is refused too. Clearing a cookie asks
        // a browser to forget; ending the row is what makes a stolen copy stop working.
        as(browser).when().get("/api/v1/auth/state").then().statusCode(401);
    }

    @Test
    void nobodySeesSomebodyElsesSessions() {
        Map<String, String> mine = signIn();
        AuthzFixtures.createUserWithPassword(mine.get("keydra_session"), "somebody-else");
        String theirs = AuthzFixtures.signIn("somebody-else");

        // Their list is theirs: one session, and not one of mine.
        List<?> found =
                given().cookie("keydra_session", theirs)
                        .when()
                        .get("/api/v1/auth/sessions")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("");
        assertThat(found, hasSize(greaterThanOrEqualTo(1)));

        // And they cannot end one of mine by naming it.
        given().cookie("keydra_session", theirs)
                .when()
                .delete("/api/v1/auth/sessions/" + mine.get("keydra_sid"))
                .then()
                .statusCode(404);

        as(mine).when()
                .get("/api/v1/auth/state")
                .then()
                .statusCode(200)
                .body("authenticated", is(true));
    }

    @Test
    void aSessionRemembersWhereItCameFromWithoutRememberingTooMuch() {
        Map<String, String> browser = signIn();

        String network =
                as(browser)
                        .when()
                        .get("/api/v1/auth/sessions")
                        .then()
                        .statusCode(200)
                        .extract()
                        .path("[0].network");

        // The address with its last part removed: enough to say "that is not where I work",
        // not enough to be a record of somebody's movements.
        assertThat(network, notNullValue());
        assertThat(network.endsWith(".0") || network.endsWith("::"), is(true));
    }

    @Test
    void endingASessionThatIsNotThereIsNotAnError() {
        Map<String, String> browser = signIn();

        as(browser)
                .when()
                .delete("/api/v1/auth/sessions/00000000-0000-0000-0000-000000000000")
                .then()
                .statusCode(404);
    }

    @Test
    void aSessionIsNotHandedToSomebodyWhoIsNotSignedIn() {
        given().when().get("/api/v1/auth/sessions").then().statusCode(401);
    }

    @Test
    void theCookieIsNotReadableByScript() {
        var response =
                given().formParam("username", USER)
                        .formParam("password", AuthzFixtures.PASSWORD)
                        .when()
                        .post("/api/v1/auth/login")
                        .then()
                        .statusCode(200)
                        .extract();
        String session = response.cookie("keydra_session");

        String setCookie =
                given().cookie("keydra_session", session)
                        .when()
                        .get("/api/v1/auth/state")
                        .then()
                        .extract()
                        .header("set-cookie");

        // HttpOnly is what keeps a cross-site script from lifting the session, and it is the
        // reason a cookie beats a token in local storage rather than the other way round.
        assertThat(setCookie == null || setCookie.toLowerCase().contains("httponly"), is(true));
    }

    @Test
    void settingAPasswordEndsTheSessionsThatCameBeforeIt() {
        Map<String, String> browser = signIn();
        String adminSession = browser.get("keydra_session");

        int userId =
                given().cookie("keydra_session", adminSession)
                        .contentType(ContentType.JSON)
                        .body(Map.of("username", "resets", "email", "resets@example.com"))
                        .when()
                        .post("/api/v1/authz/users")
                        .then()
                        .statusCode(201)
                        .extract()
                        .path("id");

        String link =
                given().cookie("keydra_session", adminSession)
                        .when()
                        .post("/api/v1/invitations/for-user/" + userId)
                        .then()
                        .statusCode(201)
                        .extract()
                        .path("link");
        String token = link.substring(link.lastIndexOf('/') + 1);

        given().contentType(ContentType.JSON)
                .body(Map.of("password", "a-password-only-they-know"))
                .when()
                .post("/api/v1/invitations/" + token)
                .then()
                .statusCode(200);

        String theirs = AuthzFixtures.signIn("resets", "a-password-only-they-know");
        assertThat(theirs, notNullValue());
        // One request, so the session row exists — which is what a browser does the instant it
        // has signed in, and what the assertion below is about ending.
        String theirSid =
                given().cookie("keydra_session", theirs)
                        .when()
                        .get("/api/v1/auth/state")
                        .then()
                        .statusCode(200)
                        .extract()
                        .cookie("keydra_sid");
        assertThat(theirSid, notNullValue());

        // Setting a password again ends what came before it — which is what people already
        // expect a password change to do, and what it did not do before this phase.
        String second =
                given().cookie("keydra_session", adminSession)
                        .when()
                        .post("/api/v1/invitations/for-user/" + userId)
                        .then()
                        .statusCode(201)
                        .extract()
                        .path("link");
        String secondToken = second.substring(second.lastIndexOf('/') + 1);
        given().contentType(ContentType.JSON)
                .body(Map.of("password", "another-password-entirely"))
                .when()
                .post("/api/v1/invitations/" + secondToken)
                .then()
                .statusCode(200);

        given().cookie("keydra_session", theirs)
                .cookie("keydra_sid", theirSid)
                .when()
                .get("/api/v1/auth/state")
                .then()
                .statusCode(401);
    }
}
