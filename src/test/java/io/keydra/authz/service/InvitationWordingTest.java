package io.keydra.authz.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import io.keydra.authz.entity.AccountInvitation;
import io.keydra.authz.entity.AppUser;
import io.keydra.mail.service.Letterhead;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * What the two letters say.
 *
 * <p>Wording is not usually worth a test. These two are, because they are the only sentences Keydra
 * writes to somebody who has never seen it, and because the parts that vary — a username, how long
 * the link lasts, which installation this is — are the parts a reader needs and the parts a
 * refactor drops silently.
 */
class InvitationWordingTest {

    private static final String LINK = "https://keydra.example.test/invitation/abc?lang=en";

    private static AppUser account() {
        AppUser user = new AppUser();
        user.username = "ada";
        user.displayName = "Ada Lovelace";
        return user;
    }

    private static Letterhead.Draft draft(
            String language, AccountInvitation.Purpose purpose, Duration validFor) {
        return InvitationWording.draft(language, account(), purpose, LINK, validFor);
    }

    @Test
    void anInvitationNamesThePersonAndTheAccountTheyHaveBeenGiven() {
        Letterhead.Draft draft =
                draft("en", AccountInvitation.Purpose.INVITATION, Duration.ofDays(7));

        assertThat(draft.paragraphs().get(0), equalTo("Hello Ada Lovelace,"));
        assertThat(String.join(" ", draft.paragraphs()), containsString("username is ada"));
        assertThat(draft.footnote(), containsString("7 days"));
    }

    /** The same letter, and none of it in English. */
    @Test
    void theTurkishOneIsTurkishThroughout() {
        Letterhead.Draft draft = draft("tr", AccountInvitation.Purpose.RESET, Duration.ofDays(7));

        assertThat(draft.language(), equalTo("tr"));
        assertThat(draft.subject(), equalTo("Keydra parolanızı yenileyin"));
        assertThat(draft.actionLabel(), equalTo("Yeni parola belirle"));
        assertThat(draft.paragraphs().get(0), equalTo("Merhaba Ada Lovelace,"));
        assertThat(draft.footnote(), containsString("7 gün"));
        assertThat(draft.footer(), not(containsString("Sent by")));
    }

    /**
     * A reset says the thing a reset does that an invitation does not.
     *
     * <p>Setting the password ends every session the account had. Somebody resetting a password
     * they think was taken is asking exactly for that and should be told it is what happens.
     */
    @Test
    void aResetSaysThatEverySessionEnds() {
        assertThat(
                String.join(
                        " ",
                        draft("en", AccountInvitation.Purpose.RESET, Duration.ofDays(7))
                                .paragraphs()),
                containsString("signs out every browser"));
        assertThat(
                String.join(
                        " ",
                        draft("tr", AccountInvitation.Purpose.RESET, Duration.ofDays(7))
                                .paragraphs()),
                containsString("oturumlar kapanır"));
    }

    /** How long the link lasts is configurable, so the sentence saying so has to follow it. */
    @Test
    void theDeadlineIsSaidInWhicheverUnitLeavesAWholeNumber() {
        assertThat(
                draft("en", AccountInvitation.Purpose.INVITATION, Duration.ofDays(1)).footnote(),
                containsString("1 day"));
        assertThat(
                draft("en", AccountInvitation.Purpose.INVITATION, Duration.ofHours(6)).footnote(),
                containsString("6 hours"));
        assertThat(
                draft("tr", AccountInvitation.Purpose.INVITATION, Duration.ofHours(6)).footnote(),
                containsString("6 saat"));
    }

    /** Which Keydra this is, for somebody who uses more than one. */
    @Test
    void theLineUnderTheCardNamesTheInstallation() {
        assertThat(
                draft("en", AccountInvitation.Purpose.INVITATION, Duration.ofDays(7)).footer(),
                equalTo("Sent by Keydra · keydra.example.test"));
    }

    /** An account nobody gave a name to is still written to. */
    @Test
    void thereIsAGreetingWithoutAName() {
        AppUser anonymous = account();
        anonymous.displayName = "  ";

        assertThat(
                InvitationWording.draft(
                                "en",
                                anonymous,
                                AccountInvitation.Purpose.INVITATION,
                                LINK,
                                Duration.ofDays(7))
                        .paragraphs()
                        .get(0),
                equalTo("Hello,"));
    }
}
