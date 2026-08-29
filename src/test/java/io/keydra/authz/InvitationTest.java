package io.keydra.authz;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import io.keydra.resources.MailRelayResource;
import io.keydra.resources.RedisTargetsResource;
import io.keydra.security.SecuredProfile;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.http.ContentType;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Nobody else chooses your password.
 *
 * <p>An administrator makes the account; the person invited chooses what they sign in with. What is
 * worth pinning is not that the happy path works but that the link behaves like a credential: it
 * works once, it stops working, and it never comes back out of the API after it has been made.
 */
@QuarkusTest
@TestProfile(SecuredProfile.class)
// As with the other secured classes: Quarkus restarts the application when the set of test
// resources changes, and restarts mid-run are where the container engine starts refusing.
@WithTestResource(RedisTargetsResource.class)
// And a relay to send to, because what the letter looks like when it arrives is half of what
// this is for. Restricted to this class along with the public URL it brings — see the resource.
@WithTestResource(MailRelayResource.class)
class InvitationTest {

    private static final String ADMIN = "invite-admin";
    private static final String CHOSEN = "a-password-only-they-know";

    private String session;

    @BeforeEach
    void setUp() {
        AuthzFixtures.deleteEverythingButRoles();
        AuthzFixtures.setUpAdministrator(ADMIN);
        session = AuthzFixtures.signIn(ADMIN);
        MailRelayResource.relay().clear();
    }

    /** Makes an account with no password at all, which is the point of the whole phase. */
    private int createAccount(String username) {
        return given().cookie("keydra_session", session)
                .contentType(ContentType.JSON)
                .body(Map.of("username", username, "email", username + "@example.com"))
                .when()
                .post("/api/v1/authz/users")
                .then()
                .statusCode(201)
                .extract()
                .path("id");
    }

    /** Asks for a link and returns the token out of it. */
    private String inviteAndTakeToken(int userId) {
        String link =
                given().cookie("keydra_session", session)
                        .when()
                        .post("/api/v1/invitations/for-user/" + userId)
                        .then()
                        .statusCode(201)
                        .body("link", notNullValue())
                        .extract()
                        .path("link");
        return link.substring(link.lastIndexOf('/') + 1);
    }

    @Test
    void anAccountMadeWithoutAPasswordCannotBeSignedInto() {
        createAccount("newcomer");

        // Not "wrong password" as a special case: the account has nothing to check against,
        // and the sign-in path has refused a passwordless row since phase 9.
        given().formParam("username", "newcomer")
                .formParam("password", CHOSEN)
                .when()
                .post("/api/v1/auth/login")
                .then()
                .statusCode(401);
    }

    @Test
    void theInvitedPersonChoosesWhatTheySignInWith() {
        int id = createAccount("newcomer");
        String token = inviteAndTakeToken(id);

        given().when()
                .get("/api/v1/invitations/" + token)
                .then()
                .statusCode(200)
                .body("usable", is(true))
                .body("username", equalTo("newcomer"))
                .body("purpose", equalTo("INVITATION"));

        given().contentType(ContentType.JSON)
                .body(Map.of("password", CHOSEN))
                .when()
                .post("/api/v1/invitations/" + token)
                .then()
                .statusCode(200)
                .body("usable", is(true));

        assertThat(AuthzFixtures.signIn("newcomer", CHOSEN), notNullValue());
    }

    @Test
    void aLinkWorksOnceAndSaysSoTheSecondTime() {
        int id = createAccount("newcomer");
        String token = inviteAndTakeToken(id);

        given().contentType(ContentType.JSON)
                .body(Map.of("password", CHOSEN))
                .when()
                .post("/api/v1/invitations/" + token)
                .then()
                .statusCode(200);

        // Gone rather than refused: there was a link, and it has been spent. Somebody who
        // followed an old mail deserves that sentence and not "wrong link".
        given().contentType(ContentType.JSON)
                .body(Map.of("password", "something-else-entirely"))
                .when()
                .post("/api/v1/invitations/" + token)
                .then()
                .statusCode(410)
                .body("refusal", equalTo("USED"));
    }

    @Test
    void askingAgainEndsTheLinkAlreadySent() {
        int id = createAccount("newcomer");
        String first = inviteAndTakeToken(id);
        String second = inviteAndTakeToken(id);

        // A link somebody forwarded last week stops working the moment a fresh one is asked
        // for; otherwise every invitation ever sent stays good until it expires.
        given().when()
                .get("/api/v1/invitations/" + first)
                .then()
                .statusCode(200)
                .body("usable", is(false))
                .body("refusal", equalTo("USED"));

        given().when()
                .get("/api/v1/invitations/" + second)
                .then()
                .statusCode(200)
                .body("usable", is(true));
    }

    @Test
    void saysNothingAboutWhoHasAnAccount() {
        // The same answer either way. Anything else is a way to ask Keydra who is here.
        given().contentType(ContentType.JSON)
                .body(Map.of("username", "nobody-by-that-name"))
                .when()
                .post("/api/v1/invitations/forgotten")
                .then()
                .statusCode(202);

        createAccount("newcomer");
        given().contentType(ContentType.JSON)
                .body(Map.of("username", "newcomer"))
                .when()
                .post("/api/v1/invitations/forgotten")
                .then()
                .statusCode(202);
    }

    @Test
    void refusesAPasswordShortEnoughToGuess() {
        int id = createAccount("newcomer");
        String token = inviteAndTakeToken(id);

        given().contentType(ContentType.JSON)
                .body(Map.of("password", "short"))
                .when()
                .post("/api/v1/invitations/" + token)
                .then()
                .statusCode(400);

        // And the link is still good, because nothing was spent on a refused password.
        given().when().get("/api/v1/invitations/" + token).then().body("usable", is(true));
    }

    @Test
    void saysWhatALinkNobodyIssuedIsWorth() {
        given().when()
                .get("/api/v1/invitations/not-a-token-anybody-made")
                .then()
                .statusCode(200)
                .body("usable", is(false))
                .body("refusal", equalTo("UNKNOWN"))
                // No account is named for a link that names none.
                .body("username", nullValue());
    }

    // --- What arrives ------------------------------------------------------

    /**
     * The shape of the message, which is the half a wording test cannot see.
     *
     * <p>Two parts, plain before HTML — the order MIME asks for, so a client that reads the first
     * part it understands reads the readable one — and a message with no plain part at all is one a
     * spam filter scores down. An invitation in a junk folder fails in the way that is hardest to
     * diagnose: the administrator sees it sent and the person sees nothing.
     */
    @Test
    void theLetterArrivesAsSomethingEveryClientCanRead() throws Exception {
        inviteAndTakeToken(createAccount("newcomer"));

        MimeMessage message = onlyMessage();
        assertThat(message.getSubject(), equalTo("You have been given a Keydra account"));
        assertThat(message.getContentType(), containsString("multipart/alternative"));

        MimeMultipart parts = (MimeMultipart) message.getContent();
        assertThat(parts.getCount(), equalTo(2));
        assertThat(parts.getBodyPart(0).getContentType(), containsString("text/plain"));
        assertThat(parts.getBodyPart(1).getContentType(), containsString("text/html"));

        String plain = (String) parts.getBodyPart(0).getContent();
        String html = (String) parts.getBodyPart(1).getContent();
        assertThat(plain, containsString(MailRelayResource.PUBLIC_URL + "/invitation/"));
        assertThat(html, containsString(MailRelayResource.PUBLIC_URL + "/invitation/"));
        // Nothing in it depends on a stylesheet arriving, which is the one thing mail clients
        // agree on stripping.
        assertThat(html, not(containsString("<style")));
    }

    /**
     * Which language, decided by the only thing that knows: the account.
     *
     * <p>The long way round on purpose. A brand-new account has never said what it reads, so the
     * only way to have an account that has is to be one: sign in, choose Turkish, and then ask for
     * a reset the way somebody who has forgotten a password does.
     */
    @Test
    void somebodyWhoReadsTurkishIsWrittenToInTurkish() throws Exception {
        int id = createAccount("newcomer");
        String token = inviteAndTakeToken(id);
        given().contentType(ContentType.JSON)
                .body(Map.of("password", CHOSEN))
                .when()
                .post("/api/v1/invitations/" + token)
                .then()
                .statusCode(200);

        String theirs = AuthzFixtures.signIn("newcomer", CHOSEN);
        given().cookie("keydra_session", theirs)
                .contentType(ContentType.JSON)
                .body(Map.of("name", "language", "value", "tr"))
                .when()
                .post("/api/v1/preferences")
                .then()
                .statusCode(200)
                .body(is("true"));

        MailRelayResource.relay().clear();
        given().contentType(ContentType.JSON)
                .body(Map.of("username", "newcomer"))
                .when()
                .post("/api/v1/invitations/forgotten")
                .then()
                .statusCode(202);

        MimeMessage message = onlyMessage();
        // A subject outside ASCII is an encoded word on the wire and a sentence in an inbox.
        assertThat(message.getSubject(), equalTo("Keydra parolanızı yenileyin"));
        String plain = (String) ((MimeMultipart) message.getContent()).getBodyPart(0).getContent();
        assertThat(plain, containsString("Merhaba"));
        // And the page the link opens is in the language the letter was written in.
        assertThat(plain, containsString("?lang=tr"));
    }

    /** The one message that has arrived, parsed the way a mail client would parse it. */
    private static MimeMessage onlyMessage() throws Exception {
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .untilAsserted(() -> assertThat(MailRelayResource.relay().messages(), hasSize(1)));
        byte[] raw = MailRelayResource.relay().messages().get(0).getBytes(StandardCharsets.UTF_8);
        return new MimeMessage(
                Session.getInstance(new Properties()), new ByteArrayInputStream(raw));
    }

    @Test
    void onlySomebodyWhoMayManageAccountsCanInvite() {
        int id = createAccount("newcomer");

        given().when().post("/api/v1/invitations/for-user/" + id).then().statusCode(401);
    }
}
