package io.keydra.connections.rest;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;

import io.keydra.connections.ConnectionFixtures;
import io.keydra.resources.RedisTargetsResource;
import io.keydra.schedule.ScheduleFixtures;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.HashMap;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * A target that has to be named before anything empties it.
 *
 * <p>Without security on, deliberately. Naming a target is not a question about who somebody is —
 * it is asked of everybody, including whatever holds every permission there is — and running these
 * on an open instance is what proves that.
 *
 * <p>The one about a scheduled copy is the reason this class exists at the time it does. The guard
 * was written to ask about the target a schedule runs against, which for a copy is the server the
 * keys come *from*; the far end is where they land, unattended, every night, over whatever is
 * already there.
 */
@QuarkusTest
@WithTestResource(RedisTargetsResource.class)
class GuardedTargetTest {

    private Long guarded;
    private Long ordinary;

    @BeforeEach
    void setUp() {
        ScheduleFixtures.deleteEverySchedule();
        ConnectionFixtures.deleteAllProfiles();
        RedisTargetsResource.flushRedis();

        String host =
                ConfigProvider.getConfig().getValue(RedisTargetsResource.REDIS_HOST, String.class);
        int port =
                ConfigProvider.getConfig().getValue(RedisTargetsResource.REDIS_PORT, Integer.class);
        guarded = ConnectionFixtures.createProfile("orders-prod", host, port);
        ordinary = ConnectionFixtures.createProfile("orders-cache", host, port);
        ConnectionFixtures.guard(guarded);
    }

    @Test
    void aPurgeWithoutTheNameIsRefused() {
        RedisTargetsResource.execRedis("SET", "doomed:1", "value");

        purge(guarded, Map.of("match", "doomed:*"))
                .then()
                // 409 rather than 400: the request is well formed and the caller is allowed to
                // make it. What is missing is a confirmation, and the state that makes it
                // necessary belongs to the target.
                .statusCode(409)
                .body("message", containsString("orders-prod"));

        org.junit.jupiter.api.Assertions.assertEquals(
                "1", RedisTargetsResource.execRedis("EXISTS", "doomed:1").trim());
    }

    @Test
    void theNameHasToBeExact() {
        // A comparison that trimmed would accept a trailing space from a paste, and one that
        // ignored case would accept a name from somebody who was not reading — which is the whole
        // population this is for.
        purge(guarded, confirmed("orders-prod ")).then().statusCode(409);
        purge(guarded, confirmed("Orders-Prod")).then().statusCode(409);
        purge(guarded, confirmed("orders-cache")).then().statusCode(409);
    }

    @Test
    void theNameIsWhatLetsItThrough() {
        RedisTargetsResource.execRedis("SET", "doomed:1", "value");

        purge(guarded, confirmed("orders-prod"))
                .then()
                .statusCode(200)
                .body("affected", equalTo(1));
    }

    @Test
    void aTargetThatDoesNotAskIsUnchanged() {
        RedisTargetsResource.execRedis("SET", "spare:1", "value");

        purge(ordinary, Map.of("match", "spare:*"))
                .then()
                .statusCode(200)
                .body("affected", equalTo(1));
    }

    @Test
    void aScheduledCopyNamesTheServerItWritesInto() {
        // The half phase 59 left out. The job supplies both names itself when it fires — which is
        // right, because nobody is present at three in the morning — so the moment it is written
        // is the only moment anybody can be asked at all.
        Map<String, Object> copy = new HashMap<>();
        copy.put("name", "nightly-copy");
        copy.put("connectionId", ordinary);
        copy.put("jobType", "COPY_KEYS");
        copy.put("cron", "0 3 * * *");
        copy.put("enabled", true);
        copy.put("settings", "{\"targetConnectionId\":" + guarded + ",\"match\":\"*\"}");

        given().contentType(ContentType.JSON)
                .body(copy)
                .when()
                .post("/api/v1/schedules?connectionId=" + ordinary)
                .then()
                .statusCode(409)
                .body("message", containsString("orders-prod"));

        given().when().get("/api/v1/schedules").then().statusCode(200).body("", hasSize(0));

        copy.put("confirmSecond", "orders-prod");
        given().contentType(ContentType.JSON)
                .body(copy)
                .when()
                .post("/api/v1/schedules?connectionId=" + ordinary)
                .then()
                .statusCode(201);

        given().when()
                .get("/api/v1/schedules")
                .then()
                .body("", hasSize(1))
                .body("[0].name", equalTo("nightly-copy"));
    }

    private static Map<String, Object> confirmed(String name) {
        return Map.of("match", "doomed:*", "confirmTarget", name);
    }

    private static io.restassured.response.Response purge(Long connectionId, Map<String, ?> body) {
        return given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/connections/" + connectionId + "/keys/purge");
    }
}
