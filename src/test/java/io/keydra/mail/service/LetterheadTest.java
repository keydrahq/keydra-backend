package io.keydra.mail.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The frame a letter is written on.
 *
 * <p>No application here: the whole class is a string in and two strings out, and the things worth
 * pinning about it are the ones a mail client would otherwise be the first to notice. Somebody
 * adding a heading later should not be able to reach for a stylesheet without a test saying no.
 */
class LetterheadTest {

    private static final String LINK = "https://keydra.example.test/invitation/abc?lang=en";

    private final Letterhead letterhead = new Letterhead("en");

    private Letter letter(String language, String heading, List<String> paragraphs) {
        return letterhead.compose(
                new Letterhead.Draft(
                        language,
                        "A subject",
                        "The line beside it",
                        heading,
                        paragraphs,
                        "Do the thing",
                        LINK,
                        "The link works once.",
                        "Sent by Keydra"));
    }

    /**
     * The rule the whole class exists for.
     *
     * <p>A stylesheet is stripped by Outlook.com and unreliable in Gmail, so a letter that depended
     * on one would look right in whatever the person who wrote it uses and wrong in the inbox that
     * matters. There is no way to assert "this renders" — there is a way to assert nobody reached
     * for the mechanism that does not arrive.
     */
    @Test
    void nothingInALetterDependsOnAStylesheetArriving() {
        String html = letter("en", "A heading", List.of("A paragraph")).html();

        assertThat(html, not(containsString("<style")));
        assertThat(html, not(containsString("class=")));
        assertThat(html, not(containsString("<link")));
        // Nor on an image: an SVG is stripped and a PNG arrives blocked by default.
        assertThat(html, not(containsString("<img")));
        // And no color-scheme, because there is no stylesheet to carry a dark one — claiming it
        // stops a client applying its own inversion, which is worse than the inversion.
        assertThat(html, not(containsString("color-scheme")));
    }

    @Test
    void bothBodiesCarryTheOneAddressThatHasToBeRight() {
        Letter letter = letter("en", "A heading", List.of("A paragraph"));

        assertThat(letter.html(), containsString("href=\"" + LINK + "\""));
        // Printed as text under the button as well: clients block links and proxies rewrite them.
        assertThat(letter.html().indexOf(LINK), not(equalTo(letter.html().lastIndexOf(LINK))));
        assertThat(letter.text(), containsString(LINK));
        assertThat(letter.text(), containsString("A paragraph"));
    }

    /**
     * A display name is something somebody typed, and this is the one place it is pasted into
     * markup.
     */
    @Test
    void aNameThatLooksLikeMarkupIsNotMarkup() {
        Letter letter =
                letter("en", "A heading", List.of("Hello <script>alert(1)</script> Ada & co,"));

        assertThat(letter.html(), not(containsString("<script>")));
        assertThat(letter.html(), containsString("&lt;script&gt;"));
        assertThat(letter.html(), containsString("Ada &amp; co"));
        // The text part is not markup and is left exactly as it was written.
        assertThat(letter.text(), containsString("<script>alert(1)</script>"));
        assertThat(letter.text(), containsString("Ada & co"));
    }

    @Test
    void theLetterSaysWhichLanguageItIsIn() {
        assertThat(
                letter("tr", "Bir başlık", List.of("Bir paragraf")).html(),
                containsString("<html lang=\"tr\">"));
    }

    /** Which language to write in, given whatever is known about the reader. */
    @Test
    void whatSomebodyPrefersWinsAndNonsenseDoesNot() {
        assertThat(letterhead.language("tr"), equalTo("tr"));
        assertThat(letterhead.language("TR"), equalTo("tr"));
        // Never set, and a language Keydra does not speak, are the same answer.
        assertThat(letterhead.language(null), equalTo("en"));
        assertThat(letterhead.language("kw"), equalTo("en"));
    }

    /** A misconfigured default is still a letter somebody can read. */
    @Test
    void anInstanceConfiguredForALanguageNobodyWroteFallsBackToEnglish() {
        assertThat(new Letterhead("kw").language(null), equalTo("en"));
        assertThat(new Letterhead("tr").language(null), equalTo("tr"));
    }
}
