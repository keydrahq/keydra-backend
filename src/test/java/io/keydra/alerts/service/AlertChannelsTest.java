package io.keydra.alerts.service;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import io.keydra.alerts.AlertFixtures;
import io.keydra.alerts.FakeWhatsApp;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The three channels a team is actually watching.
 *
 * <p>WhatsApp is exercised end to end against a stand-in for the Cloud API, because it is the one
 * built out of an HTTP request here rather than out of a component — which means the request is
 * this codebase's to get right. Telegram and Slack are components, so what is worth testing is what
 * this codebase decides: that a delivery missing the one field its kind needs is refused while
 * somebody is still looking at the form, and that a token, once stored, is never handed back.
 */
@QuarkusTest
@WithTestResource(FakeWhatsApp.class)
class AlertChannelsTest {

    @BeforeEach
    void setUp() {
        AlertFixtures.deleteEveryRule();
        FakeWhatsApp.forget();
    }

    private static Map<String, Object> delivery(String name, String kind) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("kind", kind);
        return body;
    }

    private static int create(Map<String, Object> body) {
        return given().contentType(ContentType.JSON)
                .body(body)
                .when()
                .post("/api/v1/alert-deliveries")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    @Test
    void sendsAWhatsAppMessageTheCloudApiWouldAccept() {
        Map<String, Object> body = delivery("on-call phone", "WHATSAPP");
        body.put("apiToken", "EAAG-an-access-token");
        body.put("recipient", "+905551112233");
        body.put("senderId", "123456789012345");
        int id = create(body);

        given().when()
                .post("/api/v1/alert-deliveries/" + id + "/check")
                .then()
                .statusCode(200)
                .body("working", equalTo(true));

        // The identity sending the message is part of the address, not part of the body.
        assertThat(FakeWhatsApp.lastPath(), containsString("/123456789012345/messages"));
        // The token travels as a bearer credential, which is the only place the API looks.
        assertThat(FakeWhatsApp.lastAuthorization(), equalTo("Bearer EAAG-an-access-token"));

        String sent = FakeWhatsApp.lastBody();
        assertThat(sent, containsString("\"messaging_product\":\"whatsapp\""));
        assertThat(sent, containsString("\"to\":\"+905551112233\""));
        assertThat(sent, containsString("\"type\":\"text\""));
        // Off deliberately: a preview would have Meta fetch whatever a target name looked
        // like a link to.
        assertThat(sent, containsString("\"preview_url\":false"));
    }

    @Test
    void refusesAChatDeliveryMissingTheOneFieldItNeeds() {
        Map<String, Object> withoutToken = delivery("team chat", "TELEGRAM");
        withoutToken.put("recipient", "-1001234567890");
        given().contentType(ContentType.JSON)
                .body(withoutToken)
                .when()
                .post("/api/v1/alert-deliveries")
                .then()
                .statusCode(409)
                .body("message", containsString("token"));

        Map<String, Object> withoutChannel = delivery("team chat", "SLACK");
        withoutChannel.put("apiToken", "xoxb-a-bot-token");
        given().contentType(ContentType.JSON)
                .body(withoutChannel)
                .when()
                .post("/api/v1/alert-deliveries")
                .then()
                .statusCode(409)
                .body("message", containsString("channel"));

        // WhatsApp needs a third thing, and says which: the API sends *from* an identity.
        Map<String, Object> withoutSender = delivery("phone", "WHATSAPP");
        withoutSender.put("apiToken", "EAAG-an-access-token");
        withoutSender.put("recipient", "+905551112233");
        given().contentType(ContentType.JSON)
                .body(withoutSender)
                .when()
                .post("/api/v1/alert-deliveries")
                .then()
                .statusCode(409)
                .body("message", containsString("phone number id"));
    }

    @Test
    void neverHandsAChatTokenBack() {
        Map<String, Object> body = delivery("team chat", "TELEGRAM");
        body.put("apiToken", "1234:a-bot-token");
        body.put("recipient", "-1001234567890");
        int id = create(body);

        given().when()
                .get("/api/v1/alert-deliveries")
                .then()
                .statusCode(200)
                .body("find { it.id == " + id + " }.hasApiToken", equalTo(true))
                .body("find { it.id == " + id + " }.apiToken", nullValue())
                // The recipient is not a secret in any of the three, so a list can say where
                // alerts go without decrypting anything.
                .body("find { it.id == " + id + " }.recipient", equalTo("-1001234567890"))
                .body("find { it.id == " + id + " }.describedAs", containsString("-1001234567890"));
    }

    @Test
    void anEditThatLeavesTheTokenOutKeepsIt() {
        Map<String, Object> body = delivery("team chat", "SLACK");
        body.put("apiToken", "xoxb-a-bot-token");
        body.put("recipient", "#alerts");
        int id = create(body);

        Map<String, Object> renamed = delivery("team chat (europe)", "SLACK");
        renamed.put("recipient", "#alerts-europe");
        given().contentType(ContentType.JSON)
                .body(renamed)
                .when()
                .put("/api/v1/alert-deliveries/" + id)
                .then()
                .statusCode(200)
                // The form arrives with the token empty because the API never returned it.
                // Treating that as "clear it" would silence every rule pointing here.
                .body("hasApiToken", equalTo(true))
                .body("recipient", equalTo("#alerts-europe"));
    }

    @Test
    void writesAChannelTheWaySlackDoesWhicheverWayItWasTyped() {
        assertThat(AlertSender.channelOf("alerts"), equalTo("#alerts"));
        assertThat(AlertSender.channelOf("#alerts"), equalTo("#alerts"));
        assertThat(AlertSender.channelOf("  alerts  "), equalTo("#alerts"));
    }

    /**
     * Several numbers, written the way anybody writes a list of people.
     *
     * <p>The mail field has always taken a comma-separated list because that is what a To: line is;
     * WhatsApp takes one number per request, which is an argument for sending several requests
     * rather than for making somebody create four deliveries.
     */
    @Test
    void readsSeveralRecipientsHoweverTheyWereWrittenDown() {
        assertThat(
                AlertSender.recipientsOf("+905551112233, +905554445566"),
                contains("+905551112233", "+905554445566"));
        // Semicolons because a mail client taught somebody that habit, and a trailing separator
        // because everybody does that.
        assertThat(
                AlertSender.recipientsOf("+905551112233; +905554445566;"),
                contains("+905551112233", "+905554445566"));
        assertThat(AlertSender.recipientsOf("  +905551112233  "), contains("+905551112233"));
    }

    /** Nothing written down is nobody to send to, rather than one recipient called "". */
    @Test
    void readsNothingFromNothing() {
        assertThat(AlertSender.recipientsOf(null), empty());
        assertThat(AlertSender.recipientsOf("   "), empty());
        assertThat(AlertSender.recipientsOf(",,"), empty());
        // A direct message is addressed with an at sign, and must not gain a hash.
        assertThat(AlertSender.channelOf("@oncall"), equalTo("@oncall"));
    }
}
