package io.keydra.authz;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.notNullValue;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.vertx.VertxContextSupport;
import io.restassured.http.ContentType;
import io.smallrye.mutiny.Uni;
import java.util.Map;

/**
 * Empties the authorization tables so each test starts from a known state.
 *
 * <p>Everything but the roles: those are written from code at every start, and a test that deleted
 * them would be testing an application that cannot happen.
 */
public final class AuthzFixtures {

    private AuthzFixtures() {}

    /** Long enough that the endpoint's own minimum does not become the thing under test. */
    public static final String PASSWORD = "a-password-nobody-guesses";

    /**
     * Creates the first account, which is the only way an empty instance gets one.
     *
     * <p>Here rather than on a test class because more than one test needs an administrator before
     * it can say anything, and a helper that lives on a test is a helper only that test's package
     * can reach.
     */
    public static void setUpAdministrator(String username) {
        given().contentType(ContentType.JSON)
                .body(Map.of("username", username, "password", PASSWORD))
                .when()
                .post("/api/v1/auth/setup")
                .then()
                .statusCode(201);
    }

    /**
     * An ordinary account with a password and no grants at all.
     *
     * <p>For the tests about refusal: somebody who is unmistakably signed in and unmistakably not
     * allowed, which is the only way to prove a refusal came from the grant tables rather than from
     * nobody being there.
     */
    public static void createUserWithPassword(String adminSession, String username) {
        given().cookie("keydra_session", adminSession)
                .contentType(ContentType.JSON)
                .body(Map.of("username", username, "password", PASSWORD))
                .when()
                .post("/api/v1/authz/users")
                .then()
                .statusCode(201);
    }

    public static String signIn(String username) {
        return signIn(username, PASSWORD);
    }

    /** The session cookie, which is what every request in a secured test carries. */
    public static String signIn(String username, String password) {
        return given().formParam("username", username)
                .formParam("password", password)
                .when()
                .post("/api/v1/auth/login")
                .then()
                .statusCode(200)
                .cookie("keydra_session", notNullValue())
                .extract()
                .cookie("keydra_session");
    }

    /** Order matters — a grant points at a role and a subject, so it goes first. */
    private static final String[] IN_ORDER = {
        // Not an account's row at all, and here for exactly that reason: the sign-in policy
        // outlives every account, so a class that turned a second factor on would hand the next
        // class an instance where nobody can do anything.
        "delete from SignInPolicy",
        "delete from UserSession",
        "delete from AccountInvitation",
        "delete from Grant",
        "delete from GroupMembership",
        "delete from ServerGroupMember",
        "delete from ServerGroup",
        "delete from ProviderGroupMapping",
        "delete from IdentityProviderConfig",
        "delete from UserGroup",
        "delete from AppUser"
    };

    public static void deleteEverythingButRoles() {
        try {
            VertxContextSupport.subscribeAndAwait(
                    () ->
                            Panache.withTransaction(
                                    () -> {
                                        Uni<Integer> chain = Uni.createFrom().item(0);
                                        for (String statement : IN_ORDER) {
                                            chain = chain.flatMap(ignored -> execute(statement));
                                        }
                                        return chain;
                                    }));
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not clear the authorization tables", failure);
        }
    }

    private static Uni<Integer> execute(String statement) {
        return Panache.getSession()
                .flatMap(session -> session.createQuery(statement).executeUpdate());
    }
}
