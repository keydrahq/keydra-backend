package io.keydra.about.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import io.keydra.AbstractTestBase;
import io.quarkus.test.common.http.TestHTTPEndpoint;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.time.Duration;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestHTTPEndpoint(About.class)
class AboutTest extends AbstractTestBase {

    @Test
    void returnsApplicationIdentityAndBuildMetadata() {
        given().when()
                .get()
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("name", is("Keydra"))
                .body("version", is("0.0.1-SNAPSHOT"))
                .body("build.timestamp", not(emptyOrNullString()))
                .body("build.commit", not(emptyOrNullString()))
                .body("build.javaVersion", is("21"))
                .body("build.quarkusVersion", startsWith("3.33"));
    }

    @Test
    void saysWhatItExportsAndWhereItGoes() {
        given().when()
                .get()
                .then()
                .statusCode(200)
                // Answered here so nobody has to read a deployment manifest to find out
                // whether this build is exporting anything.
                .body("observability.metricsPath", is("/q/metrics"))
                // Nowhere is configured in a test, and off is a real answer rather than a
                // missing one.
                .body("observability.traces", is(false))
                .body("observability.tracesTo", nullValue());
    }

    @Test
    void saysWhichInstanceThisIsAndWhetherItDoesTheSharedWork() {
        // Awaited, not asserted outright: the lease is taken a moment after the application
        // starts, and a test that runs in that moment would be measuring the clock.
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                given().when()
                                        .get()
                                        .then()
                                        .statusCode(200)
                                        // Where a schedule did not run, this is the first
                                        // question: which process was supposed to run it.
                                        .body("instance.id", not(emptyOrNullString()))
                                        .body("instance.leader", is(true))
                                        .body("instance.chores", not(emptyOrNullString())));
    }

    @Test
    void exposesExactlyTheDocumentedShape() {
        given().when()
                .get()
                .then()
                .statusCode(200)
                .body("size()", is(5))
                .body("build.size()", is(4))
                .body("instance.size()", is(3))
                .body("observability.size()", is(4));
    }
}
