package io.keydra.common;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

/**
 * The rules the browser is told to enforce.
 *
 * <p>Asserted on a live response rather than read out of configuration, because a header that is
 * configured and not sent is the same as one nobody wrote. Every one of these was absent, which is
 * a whole category of defence left switched off rather than a setting somebody got wrong.
 */
@QuarkusTest
class SecurityHeadersTest {

    @Test
    void refusesToBeFramed() {
        // Keydra draws a console that runs commands against somebody's server. A page that could
        // frame it could steer a click into one, so this is said twice — once for browsers that
        // read the policy and once for browsers that read the header.
        given().when()
                .get("/api/v1/auth/state")
                .then()
                .header("X-Frame-Options", equalTo("DENY"))
                .header("Content-Security-Policy", containsString("frame-ancestors 'none'"));
    }

    @Test
    void refusesToLetTheBrowserGuessAContentType() {
        given().when()
                .get("/api/v1/auth/state")
                .then()
                .header("X-Content-Type-Options", equalTo("nosniff"));
    }

    @Test
    void keepsAddressesOutOfTheReferrerHeader() {
        // A key name, a glob and a channel all live in the address bar here, and a Referer header
        // carries the address to whoever is linked to.
        given().when()
                .get("/api/v1/auth/state")
                .then()
                .header("Referrer-Policy", equalTo("strict-origin-when-cross-origin"));
    }

    @Test
    void stopsAnythingUnderTheApiFromBeingCached() {
        // Answers under /api are about one caller and are never the same for two of them. Without
        // this a proxy in front of Keydra may hold one person's answer and hand it to the next.
        given().when()
                .get("/api/v1/auth/state")
                .then()
                .header("Cache-Control", equalTo("no-store"));
    }

    @Test
    void namesAPolicyRatherThanLeavingScriptsUnbounded() {
        given().when()
                .get("/api/v1/auth/state")
                .then()
                .header("Content-Security-Policy", containsString("default-src 'self'"))
                .header("Content-Security-Policy", containsString("object-src 'none'"))
                .header("Content-Security-Policy", containsString("base-uri 'self'"));
    }
}
