package io.keydra.telemetry;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

import io.keydra.connections.ConnectionFixtures;
import io.keydra.resources.RedisTargetsResource;
import io.keydra.schedule.ScheduleFixtures;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.awaitility.Awaitility;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What Keydra says about itself, read the way a scraper reads it.
 *
 * <p>Against the real endpoint rather than against the registry, because half of what this phase
 * promises is the format: a meter that exists in the registry and is named something else in the
 * scrape is a dashboard that draws nothing.
 */
@QuarkusTest
@WithTestResource(RedisTargetsResource.class)
class MetricsTest {

    private Long target;

    @BeforeEach
    void setUp() {
        ScheduleFixtures.deleteEverySchedule();
        ConnectionFixtures.deleteAllProfiles();

        String host =
                ConfigProvider.getConfig().getValue(RedisTargetsResource.REDIS_HOST, String.class);
        int port =
                ConfigProvider.getConfig().getValue(RedisTargetsResource.REDIS_PORT, Integer.class);
        target = ConnectionFixtures.createProfile("measured", host, port);
    }

    @Test
    void theScrapeCarriesKeydrasOwnMetersAndNotOnlyTheRuntimes() {
        String scrape = scrape();

        assertThat(scrape, containsString("keydra_targets"));
        assertThat(scrape, containsString("keydra_chores"));
        // The runtime's own, which is half the reason for using the standard extension rather
        // than counting things by hand.
        assertThat(scrape, containsString("jvm_memory_used_bytes"));
    }

    @Test
    void everyMeterKeydraMakesSaysWhichInstanceMadeIt() {
        // Behind a load balancer this is the difference between a graph and a guess. Under
        // its own name, because a scraper labels what it collects "instance" and renames
        // anything that arrives already carrying one.
        assertThat(scrape(), containsString("keydra_instance=\""));
    }

    @Test
    void theInstanceDoingTheChoresIsTheOneReportingThatItDoes() {
        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> assertThat(valueOf("keydra_chores"), greaterThanOrEqualTo(1.0)));
    }

    @Test
    void aScheduleThatRanMovedACounter() {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "Measured sweep");
        body.put("connectionId", target);
        body.put("jobType", "FLUSH_DATABASE");
        // Three in the morning: this test presses the button rather than waiting for a clock.
        body.put("cron", "0 3 * * *");
        body.put("enabled", true);
        body.put("settings", "{\"match\":\"nothing:*\"}");
        Integer id =
                given().contentType(ContentType.JSON)
                        .body(body)
                        .when()
                        .post("/api/v1/schedules?connectionId=" + target)
                        .then()
                        .statusCode(201)
                        .extract()
                        .path("id");

        given().when()
                .post("/api/v1/schedules/" + id + "/run?connectionId=" + target)
                .then()
                .statusCode(200);

        Awaitility.await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(
                        () -> {
                            String scrape = scrape();
                            assertThat(scrape, containsString("keydra_schedule_runs_total"));
                            assertThat(scrape, containsString("outcome=\"DONE\""));
                            // A run somebody pressed is labelled as one: a schedule that only
                            // ever ran because a person pressed it has not run.
                            assertThat(scrape, containsString("manual=\"true\""));
                        });
    }

    private static String scrape() {
        return given().when().get("/q/metrics").then().statusCode(200).extract().asString();
    }

    /**
     * The largest value a meter has in the scrape, whatever its labels.
     *
     * <p>The largest rather than the first, because a suite that restarts the application leaves
     * the previous run's instance in the registry: a series per instance is exactly what the label
     * is for, and the question here is whether any instance says it holds the chores — which, in
     * one process, means this one.
     */
    private static double valueOf(String meter) {
        Matcher found =
                Pattern.compile(
                                "^" + Pattern.quote(meter) + "\\{[^}]*}\\s+([0-9.eE+-]+)$",
                                Pattern.MULTILINE)
                        .matcher(scrape());
        double highest = Double.NaN;
        while (found.find()) {
            double value = Double.parseDouble(found.group(1));
            if (Double.isNaN(highest) || value > highest) {
                highest = value;
            }
        }
        return highest;
    }
}
