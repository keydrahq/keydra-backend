package io.keydra.common;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

import io.keydra.AbstractTestBase;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

@QuarkusTest
class HealthTest extends AbstractTestBase {

    @Test
    void livenessIsUp() {
        given().when()
                .get("/q/health/live")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("status", is("UP"));
    }

    @Test
    void readinessIsUp() {
        given().when()
                .get("/q/health/ready")
                .then()
                .statusCode(200)
                .contentType(ContentType.JSON)
                .body("status", is("UP"));
    }
}
