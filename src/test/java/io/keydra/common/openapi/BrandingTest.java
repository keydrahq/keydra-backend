package io.keydra.common.openapi;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.startsWith;

import io.keydra.AbstractTestBase;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.Test;

@QuarkusTest
class BrandingTest extends AbstractTestBase {

    @Test
    void openApiDocumentCarriesTheLogoExtension() {
        given().accept(ContentType.JSON)
                .when()
                .get("/api/openapi")
                .then()
                .statusCode(200)
                .body("info.title", equalTo("Keydra API"))
                .body("info.'x-logo'.url", equalTo(OpenApiBranding.LOGO_URL))
                .body("info.'x-logo'.altText", equalTo("Keydra"));
    }

    @Test
    void swaggerUiStylesheetKeepsVendoredRulesAndKeydraOverrides() {
        // Quarkus replaces this stylesheet wholesale with META-INF/branding/
        // smallrye-open-api-ui.css, so the vendored rules have to travel with our
        // overrides. Losing either half is a visible regression in Swagger UI.
        String css =
                given().when()
                        .get("/q/swagger-ui/style.css")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString();

        // vendored
        assertThat(css, containsString("background-color: #343a40"));
        assertThat(css, containsString("content: \"Swagger UI\""));
        assertThat(css, containsString("background: #fafafa"));
        // Keydra overrides that stop the wide wordmark from being squashed
        assertThat(css, containsString("#swaggerUiLogoLink"));
        assertThat(css, containsString("width: auto"));
    }

    @Test
    void logoIsServedAsAStaticResource() {
        given().when()
                .get(OpenApiBranding.LOGO_URL)
                .then()
                .statusCode(200)
                .contentType(startsWith("image/svg+xml"));
    }
}
