package io.keydra.engine;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;

import io.keydra.connections.ConnectionFixtures;
import io.keydra.resources.RedisTargetsResource;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.Map;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The number the instances page draws its busiest edge from.
 *
 * <p>Worth pinning because a counter that is quietly wrong is worse than no counter: nobody
 * double-checks a figure on a status page, and the two ways this one could be wrong are opposite.
 * Counting a pipeline as one command would make browsing a keyspace — the busiest thing Keydra does
 * — look like the quietest. Counting it more than once would draw traffic that is not there.
 */
@QuarkusTest
@WithTestResource(RedisTargetsResource.class)
class EngineTrafficTest {

    @Inject EngineTraffic traffic;

    private int connectionId;

    @BeforeEach
    void setUp() {
        ConnectionFixtures.deleteAllProfiles();
        connectionId = createConnection();
        RedisTargetsResource.flushRedis();
        for (int i = 0; i < 20; i++) {
            RedisTargetsResource.execRedis("SET", "traffic:" + i, "value");
        }
    }

    /** A pipeline is a saving in round trips, not in commands. */
    @Test
    void aBatchCountsAsTheCommandsItHolds() {
        long before = traffic.commandCount();

        traffic.sent(7);

        assertThat(traffic.commandCount() - before, is(7L));
    }

    /**
     * The same claim, against a real server rather than against the counter alone.
     *
     * <p>Twenty keys means at least twenty questions — each one's type and its TTL — however many
     * round trips they travel in. Asserted as "at least" because the SCAN itself, and whatever else
     * a listing does, are commands too; the point is the floor, and the floor is what a batch
     * counted as one would fall through.
     */
    @Test
    void browsingAKeyspaceCountsEveryKeyItDescribed() {
        long before = traffic.commandCount();

        String body =
                given().accept("text/event-stream")
                        .when()
                        .get("/api/v1/connections/{id}/keys", connectionId)
                        .then()
                        .statusCode(200)
                        .contentType(startsWith("text/event-stream"))
                        .extract()
                        .asString();
        assertThat(body.lines().filter(line -> line.startsWith("data:")).count(), is(20L));

        assertThat(traffic.commandCount() - before, greaterThanOrEqualTo(20L));
    }

    private static int createConnection() {
        return given().contentType(ContentType.JSON)
                .body(
                        Map.of(
                                "name",
                                "traffic-target",
                                "host",
                                ConfigProvider.getConfig()
                                        .getValue(RedisTargetsResource.REDIS_HOST, String.class),
                                "port",
                                ConfigProvider.getConfig()
                                        .getValue(RedisTargetsResource.REDIS_PORT, Integer.class),
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
}
