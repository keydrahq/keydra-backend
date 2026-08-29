package io.keydra.preferences;

import static io.keydra.authz.AuthzFixtures.setUpAdministrator;
import static io.keydra.authz.AuthzFixtures.signIn;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import io.keydra.authz.AuthzFixtures;
import io.keydra.security.SecuredProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import io.restassured.specification.RequestSpecification;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A preference belongs to a person, and to nobody else.
 *
 * <p>Which is three claims worth a test: it comes back for the account that set it, it does not
 * come back for another account, and an instance with nobody signed in says there is nowhere to
 * keep it rather than refusing — because an open instance is a working instance and a theme switch
 * that errored would be the interface reporting a fault where there is a design.
 */
@QuarkusTest
@TestProfile(SecuredProfile.class)
class PreferencesTest {

    private static final String PASSWORD = "deniz-has-a-long-password";

    private String adaSession;
    private String denizSession;

    @BeforeEach
    void twoAccounts() {
        AuthzFixtures.deleteEverythingButRoles();
        setUpAdministrator("ada");
        adaSession = signIn("ada");

        asAda().contentType(ContentType.JSON)
                .body(Map.of("username", "deniz", "password", PASSWORD, "enabled", true))
                .when()
                .post("/api/v1/authz/users")
                .then()
                .statusCode(201);
        denizSession = signIn("deniz", PASSWORD);
    }

    @Test
    void comesBackForWhoeverSetIt() {
        asAda().contentType(ContentType.JSON)
                .body(Map.of("name", "keydra.theme", "value", "\"dark\""))
                .when()
                .post("/api/v1/preferences")
                .then()
                .statusCode(200)
                .body(equalTo("true"));

        asAda().when()
                .get("/api/v1/preferences")
                .then()
                .statusCode(200)
                .body("stored", equalTo(true))
                .body("preferences.'keydra.theme'", equalTo("\"dark\""));
    }

    @Test
    void isNotSomebodyElsesToRead() {
        asAda().contentType(ContentType.JSON)
                .body(Map.of("name", "keydra.theme", "value", "\"dark\""))
                .when()
                .post("/api/v1/preferences")
                .then()
                .statusCode(200);

        // Not a permission check — there is no endpoint that names an account, so the only
        // preferences anybody can reach are their own. This is that, asserted.
        asDeniz()
                .when()
                .get("/api/v1/preferences")
                .then()
                .statusCode(200)
                .body("preferences.'keydra.theme'", nullValue());
    }

    @Test
    void theLastOneWrittenIsTheOneKept() {
        set("keydra.pageSize", "200");
        set("keydra.pageSize", "500");

        asAda().when()
                .get("/api/v1/preferences")
                .then()
                .statusCode(200)
                .body("preferences.'keydra.pageSize'", equalTo("500"));
    }

    @Test
    void forgettingOneLeavesTheRest() {
        set("keydra.theme", "\"dark\"");
        set("keydra.pageSize", "500");

        asAda().when()
                .delete("/api/v1/preferences/keydra.theme")
                .then()
                .statusCode(200)
                .body(equalTo("true"));

        asAda().when()
                .get("/api/v1/preferences")
                .then()
                .statusCode(200)
                .body("preferences.'keydra.theme'", nullValue())
                .body("preferences.'keydra.pageSize'", equalTo("500"));
    }

    /**
     * Nobody signed in: nothing, and nowhere to put it.
     *
     * <p>Answered rather than refused, which is the whole reason the endpoint is
     * {@code @PermitAll}. The browser reads this as "keep your own copy", which is what it did
     * before any of this existed.
     */
    @Test
    void anAnonymousCallerIsToldThereIsNowhereToKeepIt() {
        given().when()
                .get("/api/v1/preferences")
                .then()
                .statusCode(200)
                .body("stored", equalTo(false))
                .body("preferences", equalTo(Map.of()));

        given().contentType(ContentType.JSON)
                .body(Map.of("name", "keydra.theme", "value", "\"dark\""))
                .when()
                .post("/api/v1/preferences")
                .then()
                .statusCode(200)
                .body(equalTo("false"));
    }

    private void set(String name, String value) {
        asAda().contentType(ContentType.JSON)
                .body(Map.of("name", name, "value", value))
                .when()
                .post("/api/v1/preferences")
                .then()
                .statusCode(200);
    }

    private RequestSpecification asAda() {
        return given().cookie("keydra_session", adaSession);
    }

    private RequestSpecification asDeniz() {
        return given().cookie("keydra_session", denizSession);
    }
}
