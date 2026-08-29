package io.keydra.security;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import io.keydra.connections.ConnectionFixtures;
import io.keydra.connections.persistence.EncryptedStringConverter;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Moving every stored credential onto a new key.
 *
 * <p>A key that cannot be rotated is a key nobody rotates, which after the first person leaves is
 * the same as not having one. What this proves is the property that makes it possible: an encrypted
 * value names the key that wrote it, so more than one can be readable at once and the rotation is a
 * thing that happens with the instance up.
 */
@QuarkusTest
class SecretRotationTest {

    @BeforeEach
    void setUp() {
        ConnectionFixtures.deleteAllProfiles();
    }

    @Test
    void whatIsWrittenNamesTheKeyThatWroteIt() {
        createProfile("named", "a-target-password");

        // Not enc:v1: any more. A value that does not say which key wrote it is a value that
        // can only ever be read by one, which is the whole reason rotation was impossible.
        assertThat(
                storedPassword(), startsWith("enc:v2:" + EncryptedStringConverter.currentKeyId()));
    }

    @Test
    void saysHowMuchIsOnTheCurrentKey() {
        createProfile("counted", "a-target-password");

        given().when()
                .get("/api/v1/security/encryption")
                .then()
                .statusCode(200)
                .body("currentKeyId", equalTo(EncryptedStringConverter.currentKeyId()))
                .body("onCurrentKey", greaterThan(0))
                // Nothing was written by a key that is now only read, because nothing has
                // been rotated away from in this instance.
                .body("onOtherKeys", equalTo(0));
    }

    @Test
    void aValueWrittenByAnOlderKeyIsStillReadable() {
        int id = createProfile("legacy", "written-before-ids");

        // Rewritten in the shape this used to store, which is what an instance upgrading
        // from before key ids has in every one of these columns.
        toLegacyForm(id);

        // Read through the API, so the converter is what does the reading.
        given().when()
                .get("/api/v1/connections/{id}", id)
                .then()
                .statusCode(200)
                .body("hasPassword", equalTo(true));

        given().when()
                .get("/api/v1/security/encryption")
                .then()
                .statusCode(200)
                // It counts as elsewhere, because it was not written by the current key.
                .body("onOtherKeys", greaterThan(0));
    }

    @Test
    void rotatingMovesEverythingOntoTheKeyThatWritesNow() {
        int id = createProfile("rotated", "a-target-password");
        toLegacyForm(id);
        String before = storedPassword();

        given().when()
                .post("/api/v1/security/encryption/rotate")
                .then()
                .statusCode(200)
                .body("rotated", greaterThan(0));

        String after = storedPassword();

        assertThat(after, startsWith("enc:v2:" + EncryptedStringConverter.currentKeyId()));
        // The ciphertext changed, so this really was rewritten rather than counted.
        assertThat(after, not(equalTo(before)));

        given().when()
                .get("/api/v1/security/encryption")
                .then()
                .statusCode(200)
                .body("onOtherKeys", equalTo(0));

        // And the secret still means what it meant: the profile reports one is stored, and
        // nothing anywhere returns it.
        given().when()
                .get("/api/v1/connections/{id}", id)
                .then()
                .statusCode(200)
                .body("hasPassword", equalTo(true));
    }

    @Test
    void neverReturnsASecretWhileMovingIt() {
        createProfile("quiet", "a-very-distinctive-password");

        String body =
                given().when()
                        .post("/api/v1/security/encryption/rotate")
                        .then()
                        .statusCode(200)
                        .extract()
                        .asString();

        assertThat(body.contains("a-very-distinctive-password"), equalTo(false));
    }

    // --- Helpers -----------------------------------------------------------

    private static int createProfile(String name, String password) {
        return given().contentType(ContentType.JSON)
                .body(
                        Map.of(
                                "name",
                                name,
                                "host",
                                "localhost",
                                "port",
                                6379,
                                "password",
                                password,
                                "tls",
                                false,
                                "database",
                                0,
                                "type",
                                "STANDALONE"))
                .when()
                .post("/api/v1/connections")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    /** The column as the database holds it, which is the only place the key id is visible. */
    private static String storedPassword() {
        return ConnectionFixtures.rawColumn("connection_profile", "password");
    }

    /**
     * Rewrites a stored value into the shape used before keys had ids.
     *
     * <p>The bytes are the same; only the envelope differs, because the key that writes now is also
     * the one the legacy form is read with. That is exactly the case an upgrading instance is in,
     * and the one worth proving still works.
     */
    private static void toLegacyForm(int id) {
        ConnectionFixtures.setRawColumn(
                "connection_profile",
                "password",
                id,
                current ->
                        "enc:v1:"
                                + current.substring(
                                        current.indexOf(':', ("enc:v2:").length()) + 1));
    }
}
