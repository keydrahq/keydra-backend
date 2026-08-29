package io.keydra.backup;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;

import io.keydra.connections.ConnectionFixtures;
import io.keydra.resources.RedisTargetsResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Backups that leave the machine, and come back.
 *
 * <p>Against a real server and through the real Camel components — a local directory is one of the
 * five kinds rather than a stand-in for them, and the file that lands there is written by the same
 * producer that would write it to a bucket. A test that mocked the destination would prove the
 * service calls something, and this feature exists precisely because the something is where the
 * mistakes are.
 */
@QuarkusTest
@WithTestResource(RedisTargetsResource.class)
class BackupsTest {

    private Long target;
    private Integer destination;

    @BeforeEach
    void setUp() {
        ConnectionFixtures.deleteAllProfiles();
        RedisTargetsResource.flushRedis();

        String host =
                ConfigProvider.getConfig().getValue(RedisTargetsResource.REDIS_HOST, String.class);
        int port =
                ConfigProvider.getConfig().getValue(RedisTargetsResource.REDIS_PORT, Integer.class);
        target = ConnectionFixtures.createProfile("payments-cache", host, port);

        deleteEveryDestination();
        // The local root is a temporary directory that outlives the JVM, so what a previous
        // run left there would otherwise be counted by this one.
        emptyTheLocalRoot();
        destination = createDestination("nightly", "LOCAL", Map.of("path", "test-" + target));
    }

    @Test
    void aLocalDestinationSaysItWorksBeforeAnythingDependsOnIt() {
        given().when()
                .post("/api/v1/backup-destinations/" + destination + "/check")
                .then()
                .statusCode(200)
                // The whole round trip, because credentials that can log in and not write are
                // the commonest way a destination is wrong.
                .body("reachable", equalTo(true))
                .body("message", containsString("removed"));
    }

    @Test
    void aBackupCarriesTheKeysAndSaysWhereItCameFrom() {
        RedisTargetsResource.execRedis("MSET", "a", "1", "b", "2", "c", "3");

        String name = take(Map.of("destinationId", destination));

        given().when()
                .get("/api/v1/connections/" + target + "/backups?destinationId=" + destination)
                .then()
                .statusCode(200)
                .body("", hasSize(1))
                .body("[0].name", equalTo(name))
                .body("[0].size", greaterThan(0));

        given().when()
                .get(
                        "/api/v1/connections/"
                                + target
                                + "/backups/"
                                + name
                                + "?destinationId="
                                + destination)
                .then()
                .statusCode(200)
                // Inside the file rather than only in its name, because restoring into the
                // wrong target is exactly the mistake this makes easy to make.
                .body("header.connection", equalTo("payments-cache"))
                .body("header.connectionId", equalTo(target.intValue()))
                .body("header.keydra", equalTo(1))
                .body("header.takenAt", notNullValue());
    }

    @Test
    void whatWasTakenIsWhatComesBack() {
        RedisTargetsResource.execRedis("MSET", "wanted:1", "one", "wanted:2", "two");
        RedisTargetsResource.execRedis("SET", "other", "three");

        String name = take(Map.of("destinationId", destination, "match", "wanted:*"));

        RedisTargetsResource.flushRedis();
        assertThat(RedisTargetsResource.execRedis("DBSIZE").trim(), equalTo("0"));

        given().contentType(ContentType.JSON)
                .body(Map.of("destinationId", destination, "name", name, "replace", true))
                .when()
                .post("/api/v1/connections/" + target + "/backups/restore")
                .then()
                .statusCode(200)
                .body("restored", equalTo(2))
                .body("failed", equalTo(0));

        assertThat(RedisTargetsResource.execRedis("GET", "wanted:1").trim(), equalTo("one"));
        // Only what the pattern took. A backup of part of a keyspace must not restore as if it
        // were a backup of all of it.
        assertThat(RedisTargetsResource.execRedis("EXISTS", "other").trim(), equalTo("0"));
    }

    @Test
    void theOldestFallOffWhenARetentionIsGiven() {
        RedisTargetsResource.execRedis("SET", "a", "1");

        take(Map.of("destinationId", destination, "prefix", "nightly", "keepLast", 2));
        take(Map.of("destinationId", destination, "prefix", "nightly", "keepLast", 2));
        String third =
                take(Map.of("destinationId", destination, "prefix", "nightly", "keepLast", 2));

        List<?> left =
                given().when()
                        .get(
                                "/api/v1/connections/"
                                        + target
                                        + "/backups?destinationId="
                                        + destination
                                        + "&prefix=nightly")
                        .then()
                        .statusCode(200)
                        .extract()
                        .jsonPath()
                        .getList("name");

        // A backup job that never deletes is a bucket that grows until somebody notices the
        // bill.
        assertThat(left, hasSize(2));
        assertThat(left.contains(third), equalTo(true));
    }

    @Test
    void anotherPrefixesHistoryIsLeftAlone() {
        RedisTargetsResource.execRedis("SET", "a", "1");

        take(Map.of("destinationId", destination, "prefix", "weekly"));
        take(Map.of("destinationId", destination, "prefix", "nightly", "keepLast", 1));
        take(Map.of("destinationId", destination, "prefix", "nightly", "keepLast", 1));

        // Two schedules pointed at the same place must not delete each other's history.
        given().when()
                .get(
                        "/api/v1/connections/"
                                + target
                                + "/backups?destinationId="
                                + destination
                                + "&prefix=weekly")
                .then()
                .statusCode(200)
                .body("", hasSize(1));
    }

    @Test
    void aCamelEndpointCanBeWrittenToAndSaysItCannotBeReadBack() {
        String directory = System.getProperty("java.io.tmpdir") + "/keydra-camel-test";
        Integer raw =
                createDestination(
                        "anywhere",
                        "CUSTOM",
                        Map.of("location", "file://" + directory + "?autoCreate=true"));

        RedisTargetsResource.execRedis("SET", "a", "1");

        given().when()
                .post(
                        "/api/v1/connections/"
                                + target
                                + "/backups?destinationId="
                                + raw
                                + "&prefix=raw")
                .then()
                .statusCode(200)
                .body("keys", equalTo(1));

        // The limit is stated rather than discovered at the first retention run: Camel's
        // producers send, and which components can also be listed is a property of each one.
        given().when()
                .get("/api/v1/connections/" + target + "/backups?destinationId=" + raw)
                .then()
                .statusCode(502)
                .body("message", containsString("written to and not listed"));
    }

    @Test
    void anEncryptedBackupIsUnreadableWhereItLandsAndRestoresAnyway() {
        RedisTargetsResource.execRedis("MSET", "wanted:1", "one", "wanted:2", "two");

        Integer sealed =
                createDestination(
                        "sealed",
                        "LOCAL",
                        Map.of(
                                "path",
                                "sealed-" + target,
                                "encryptionPassphrase",
                                "a-passphrase-nobody-guesses"));

        String name =
                take(Map.of("destinationId", sealed, "prefix", "nightly", "match", "wanted:*"));

        // Named so a listing can say which files need a passphrase without fetching any.
        assertThat(name.endsWith(".ndjson.gz.enc"), equalTo(true));

        given().when()
                .get("/api/v1/connections/" + target + "/backups?destinationId=" + sealed)
                .then()
                .statusCode(200)
                .body("[0].encrypted", equalTo(true));

        // The file itself gives nothing away. This is the whole claim.
        String written = readFile("sealed-" + target, name);
        assertThat(written.contains("wanted:1"), equalTo(false));
        assertThat(written.startsWith("KEYDRA-BACKUP-1"), equalTo(true));

        // And it still restores, because the destination knows the passphrase.
        RedisTargetsResource.flushRedis();
        given().contentType(ContentType.JSON)
                .body(Map.of("destinationId", sealed, "name", name, "replace", true))
                .when()
                .post("/api/v1/connections/" + target + "/backups/restore")
                .then()
                .statusCode(200)
                .body("restored", equalTo(2));

        assertThat(RedisTargetsResource.execRedis("GET", "wanted:1").trim(), equalTo("one"));
    }

    @Test
    void aPassphraseIsNeverSentBack() {
        Integer sealed =
                createDestination(
                        "quiet",
                        "LOCAL",
                        Map.of("path", "quiet-" + target, "encryptionPassphrase", "hunter2"));

        String body =
                given().when()
                        .get("/api/v1/backup-destinations")
                        .then()
                        .statusCode(200)
                        .body("find { it.id == " + sealed + " }.encrypts", equalTo(true))
                        .extract()
                        .asString();

        assertThat(body.contains("hunter2"), equalTo(false));
    }

    @Test
    void aBackupEncryptedToAKeyCannotBeReadByTheInstanceThatWroteIt() {
        RedisTargetsResource.execRedis("MSET", "wanted:1", "one", "wanted:2", "two");

        // Keydra is given only the half that encrypts.
        Map<String, String> pair = generateKeyPair();
        Integer sealed =
                createDestination(
                        "to-a-key",
                        "LOCAL",
                        Map.of(
                                "path",
                                "keyed-" + target,
                                "recipients",
                                List.of(
                                        Map.of(
                                                "label",
                                                "the safe",
                                                "publicKey",
                                                pair.get("publicKey")))));

        String name =
                take(Map.of("destinationId", sealed, "prefix", "nightly", "match", "wanted:*"));

        // It cannot look into what it just wrote: the header comes back empty rather than the
        // request failing, because "I cannot read this" is the answer.
        given().when()
                .get(
                        "/api/v1/connections/"
                                + target
                                + "/backups/"
                                + name
                                + "?destinationId="
                                + sealed)
                .then()
                .statusCode(200)
                .body("encrypted", equalTo(true))
                .body("header", org.hamcrest.Matchers.nullValue());

        RedisTargetsResource.flushRedis();

        // And it cannot restore it either, until somebody supplies the other half.
        given().contentType(ContentType.JSON)
                .body(Map.of("destinationId", sealed, "name", name, "replace", true))
                .when()
                .post("/api/v1/connections/" + target + "/backups/restore")
                .then()
                .statusCode(502)
                .body("message", containsString("private half"));

        // With the key, it restores.
        given().contentType(ContentType.JSON)
                .body(
                        Map.of(
                                "destinationId",
                                sealed,
                                "name",
                                name,
                                "replace",
                                true,
                                "privateKey",
                                pair.get("privateKey")))
                .when()
                .post("/api/v1/connections/" + target + "/backups/restore")
                .then()
                .statusCode(200)
                .body("restored", equalTo(2));

        assertThat(RedisTargetsResource.execRedis("GET", "wanted:1").trim(), equalTo("one"));
    }

    /**
     * The phase's own claim, walked end to end: two keys, one backup, and the second one opens it.
     *
     * <p>The second deliberately, not the first. A file that only its first recipient could open
     * would pass a test that restored with the key it was created with, and would be exactly the
     * bug this feature exists to not have.
     */
    @Test
    void aBackupOpensWithAnyOfTheKeysItWasWrittenTo() {
        RedisTargetsResource.execRedis("MSET", "wanted:1", "one", "wanted:2", "two");

        Map<String, String> ada = generateKeyPair();
        Map<String, String> theSafe = generateKeyPair();
        Integer sealed =
                createDestination(
                        "to-two-keys",
                        "LOCAL",
                        Map.of(
                                "path",
                                "two-keys-" + target,
                                "recipients",
                                List.of(
                                        Map.of("label", "Ada", "publicKey", ada.get("publicKey")),
                                        Map.of(
                                                "label",
                                                "the safe",
                                                "publicKey",
                                                theSafe.get("publicKey")))));

        given().when()
                .get("/api/v1/backup-destinations")
                .then()
                .body("find { it.name == 'to-two-keys' }.recipients", hasSize(2))
                .body("find { it.name == 'to-two-keys' }.recipients[1].label", equalTo("the safe"));

        String name =
                take(Map.of("destinationId", sealed, "prefix", "nightly", "match", "wanted:*"));
        RedisTargetsResource.flushRedis();

        given().contentType(ContentType.JSON)
                .body(
                        Map.of(
                                "destinationId",
                                sealed,
                                "name",
                                name,
                                "replace",
                                true,
                                "privateKey",
                                theSafe.get("privateKey")))
                .when()
                .post("/api/v1/connections/" + target + "/backups/restore")
                .then()
                .statusCode(200);

        given().when()
                .get("/api/v1/connections/" + target + "/keys?match=wanted:*")
                .then()
                .statusCode(200);
    }

    /** A list where the names repeat is a list where removing the wrong one is a coin toss. */
    @Test
    void twoKeysCalledTheSameThingAreRefused() {
        Map<String, String> first = generateKeyPair();
        Map<String, String> second = generateKeyPair();

        given().contentType(ContentType.JSON)
                .body(
                        Map.of(
                                "name",
                                "ambiguous",
                                "kind",
                                "LOCAL",
                                "recipients",
                                List.of(
                                        Map.of(
                                                "label",
                                                "the safe",
                                                "publicKey",
                                                first.get("publicKey")),
                                        Map.of(
                                                "label",
                                                "The Safe",
                                                "publicKey",
                                                second.get("publicKey")))))
                .when()
                .post("/api/v1/backup-destinations")
                .then()
                .statusCode(409)
                .body("message", containsString("Names have to differ"));
    }

    @Test
    void aDestinationCannotHaveTwoWaysIn() {
        Map<String, String> pair = generateKeyPair();

        given().contentType(ContentType.JSON)
                .body(
                        Map.of(
                                "name", "confused",
                                "kind", "LOCAL",
                                "encryptionPassphrase", "a-passphrase",
                                "recipients",
                                        List.of(
                                                Map.of(
                                                        "label",
                                                        "the safe",
                                                        "publicKey",
                                                        pair.get("publicKey")))))
                .when()
                .post("/api/v1/backup-destinations")
                .then()
                .statusCode(409)
                .body("message", containsString("not both"));
    }

    @Test
    void somethingThatIsNotAPublicKeyIsRefused() {
        given().contentType(ContentType.JSON)
                .body(
                        Map.of(
                                "name", "bad-key",
                                "kind", "LOCAL",
                                "recipients",
                                        List.of(
                                                Map.of(
                                                        "label",
                                                        "nonsense",
                                                        "publicKey",
                                                        "keydra-pk1:nonsense"))))
                .when()
                .post("/api/v1/backup-destinations")
                .then()
                .statusCode(409);
    }

    @Test
    void aDestinationMissingWhatItsKindNeedsIsRefused() {
        given().contentType(ContentType.JSON)
                .body(Map.of("name", "no-bucket", "kind", "S3"))
                .when()
                .post("/api/v1/backup-destinations")
                .then()
                .statusCode(409)
                .body("message", containsString("bucket"));

        given().contentType(ContentType.JSON)
                .body(Map.of("name", "not-a-uri", "kind", "CUSTOM", "location", "just some words"))
                .when()
                .post("/api/v1/backup-destinations")
                .then()
                .statusCode(409);
    }

    @Test
    void aSecretIsNeverSentBackAndAnAbsentOneLeavesItAlone() {
        Integer bucket =
                createDestination(
                        "some-bucket",
                        "S3",
                        Map.of("location", "backups", "accessKey", "AKIA", "secretKey", "s3cr3t"));

        given().when()
                .get("/api/v1/backup-destinations")
                .then()
                .statusCode(200)
                .body("find { it.id == " + bucket + " }.hasSecret", equalTo(true))
                // The only thing the API will say about a secret is whether there is one.
                .body(
                        "find { it.id == " + bucket + " }",
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasKey("secretKey")));

        // An edit that does not mention the secret keeps it, or renaming a destination would
        // drop its credentials.
        given().contentType(ContentType.JSON)
                .body(Map.of("name", "renamed-bucket", "kind", "S3", "location", "backups"))
                .when()
                .put("/api/v1/backup-destinations/" + bucket)
                .then()
                .statusCode(200)
                .body("hasSecret", equalTo(true));
    }

    // --- Helpers -----------------------------------------------------------

    private String take(Map<String, Object> parameters) {
        StringBuilder query = new StringBuilder();
        parameters.forEach(
                (key, value) ->
                        query.append(query.isEmpty() ? "?" : "&")
                                .append(key)
                                .append("=")
                                .append(value));
        return given().when()
                .post("/api/v1/connections/" + target + "/backups" + query)
                .then()
                .statusCode(200)
                .extract()
                .path("name");
    }

    private static Integer createDestination(String name, String kind, Map<String, ?> extra) {
        Map<String, Object> body = new HashMap<>(extra);
        body.put("name", name);
        body.put("kind", kind);
        return given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/backup-destinations")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    /** Leaves the configured backup root empty, files and directories alike. */
    private static void emptyTheLocalRoot() {
        java.nio.file.Path root =
                java.nio.file.Path.of(
                        ConfigProvider.getConfig()
                                .getValue("keydra.backup.local-root", String.class));
        if (!java.nio.file.Files.isDirectory(root)) {
            return;
        }
        try (java.util.stream.Stream<java.nio.file.Path> tree = java.nio.file.Files.walk(root)) {
            tree.sorted(java.util.Comparator.reverseOrder())
                    .filter(path -> !path.equals(root))
                    .forEach(
                            path -> {
                                try {
                                    java.nio.file.Files.deleteIfExists(path);
                                } catch (Exception ignored) {
                                    // Best effort; the assertions below say whether it worked.
                                }
                            });
        } catch (Exception unreadable) {
            throw new IllegalStateException("Could not empty " + root, unreadable);
        }
    }

    /** A pair from the server, whose private half exists in this response and nowhere else. */
    private static Map<String, String> generateKeyPair() {
        return given().when()
                .post("/api/v1/backup-destinations/keys")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getMap("", String.class, String.class);
    }

    /** One backup as it actually sits on disk, which is where the claim has to be true. */
    private static String readFile(String directory, String name) {
        java.nio.file.Path file =
                java.nio.file.Path.of(
                        ConfigProvider.getConfig()
                                .getValue("keydra.backup.local-root", String.class),
                        directory,
                        name);
        try {
            return new String(
                    java.nio.file.Files.readAllBytes(file),
                    java.nio.charset.StandardCharsets.ISO_8859_1);
        } catch (Exception unreadable) {
            throw new IllegalStateException("Could not read " + file, unreadable);
        }
    }

    private static void deleteEveryDestination() {
        given().when()
                .get("/api/v1/backup-destinations")
                .then()
                .statusCode(200)
                .extract()
                .jsonPath()
                .getList("id", Integer.class)
                .forEach(
                        id ->
                                given().when()
                                        .delete("/api/v1/backup-destinations/" + id)
                                        .then()
                                        .statusCode(204));
    }
}
