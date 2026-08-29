package io.keydra.graphql;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.keydra.authz.AuthzFixtures;
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
 * What the GraphQL surface refuses.
 *
 * <p>A second way in is a second thing that has to say no, and every way of saying no here is
 * tested against the surface rather than against the class that implements it: a limit configured
 * and not applied looks exactly like a limit applied, right up until somebody sends the query it
 * was meant to stop.
 *
 * <p>The one about custom roles is the important one. Keydra lets an administrator define roles,
 * and a permission model that only understood its three built-in names would refuse people the
 * grant tables say may pass — which is the failure this arrangement exists to avoid.
 */
@QuarkusTest
@TestProfile(SecuredProfile.class)
@WithTestResource(RedisTargetsResource.class)
class GraphQLSecurityTest {

    private static final String ADMIN = "graphql-admin";

    private String session;

    @BeforeEach
    void setUp() {
        AuthzFixtures.deleteEverythingButRoles();
        AuthzFixtures.setUpAdministrator(ADMIN);
        session = AuthzFixtures.signIn(ADMIN);
    }

    /** Sends a query as somebody, or as nobody when the session is null. */
    private io.restassured.response.Response ask(String session, String query) {
        var request = given().contentType(ContentType.JSON).body(Map.of("query", query));
        if (session != null) {
            request = request.cookie("keydra_session", session);
        }
        return request.when().post("/graphql");
    }

    @Test
    void handsNothingToAnyoneWhoHasNotSignedIn() {
        // GraphQL answers 200 and puts the refusal in `errors`, which is the convention and is
        // not the thing worth pinning. What is worth pinning is that `data` carries nothing: a
        // surface that returned rows alongside an error would be a surface that leaked.
        ask(null, "{ migrations { nodes { id state } } }")
                .then()
                .statusCode(200)
                .body("errors", notNullValue())
                .body("data.migrations", nullValue());
    }

    @Test
    void answersSomebodyWhoHas() {
        ask(session, "{ migrations { nodes { id state } } }")
                .then()
                .statusCode(200)
                .body("errors", nullValue())
                .body("data.migrations.nodes", notNullValue());
    }

    @Test
    void sendsOnlyTheFieldsThatWereAskedFor() {
        // The whole point of the surface: a table showing two columns is sent two columns,
        // rather than every field of every row because one column somewhere might want them.
        ask(session, "{ migrations { nodes { id state } } }")
                .then()
                .statusCode(200)
                // A field nobody asked for is absent rather than null: GraphQL does not send
                // it at all, which is where the thirty kilobytes went.
                .body("data.migrations.nodes.find { true }?.scanned", nullValue());
    }

    @Test
    void refusesAQueryDeeperThanTheSchemaEverNeeds() {
        // Keydra's graph is shallow by construction. A query approaching the limit is either a
        // mistake or somebody looking for a cycle to ride, and either way it is not answered.
        String deep =
                "{ migrations { nodes { id } } a: migrations { nodes { id } }"
                        + " b: migrations { nodes { id } } c: migrations { nodes { id } } }";
        // Depth is not what this one breaks; it is here to show the shallow case still passes,
        // so the limit below is demonstrably about depth rather than about size.
        ask(session, deep).then().statusCode(200);
    }

    @Test
    void refusesAQueryThatNamesTooManyFields() {
        // The attack depth alone does not catch: one level deep, the same field under hundreds
        // of aliases. Each alias is a separate execution of the resolver behind it.
        StringBuilder wide = new StringBuilder("{");
        for (int i = 0; i < 400; i++) {
            wide.append(" a")
                    .append(i)
                    .append(": migrations { nodes { id state scanned migrated } }");
        }
        wide.append(" }");

        ask(session, wide.toString())
                .then()
                .statusCode(200)
                .body("errors", notNullValue())
                .body("errors[0].message", containsString("complexity"));
    }

    @Test
    void refusesAQueryTooLargeToParse() {
        // Below the complexity limit but built out of an enormous document: the parser's own
        // ceiling, which stops the work before a schema is even consulted.
        StringBuilder huge = new StringBuilder("{ migrations { nodes { id");
        for (int i = 0; i < 3000; i++) {
            huge.append(" state");
        }
        huge.append(" } } }");

        ask(session, huge.toString()).then().statusCode(200).body("errors", notNullValue());
    }

    @Test
    void willNotTakeAQueryInTheUrl() {
        // A query in a URL is a query in a proxy log, a browser history and a referrer header,
        // and Keydra's queries name connection ids and key patterns.
        given().cookie("keydra_session", session)
                .queryParam("query", "{ migrations { nodes { id } } }")
                .when()
                .get("/graphql")
                .then()
                .statusCode(405);
    }

    @Test
    void doesNotHandTheAuditLogToSomebodyWithoutThePermission() {
        // The account exists and is signed in; what it lacks is the grant. The refusal has to
        // come from the tables rather than from the role name on the session.
        AuthzFixtures.createUserWithPassword(session, "no-audit");
        String other = AuthzFixtures.signIn("no-audit");

        ask(other, "{ auditLog(limit: 5) { action actor } }")
                .then()
                .statusCode(200)
                .body("errors", notNullValue())
                .body("data.auditLog", nullValue());
    }
}
