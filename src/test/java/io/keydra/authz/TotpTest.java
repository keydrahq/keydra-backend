package io.keydra.authz;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import io.keydra.authz.service.Totp;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * RFC 6238's own test vectors, which are the reason this is written here at all.
 *
 * <p>An implementation of a published algorithm can be proved rather than trusted, and the proof is
 * cheaper than reading somebody else's. Every vector below is from Appendix B of the RFC, taking
 * the last six digits of the eight it publishes — because six is what this uses and truncation from
 * the same integer is what produces both.
 */
class TotpTest {

    /**
     * The RFC's SHA-1 secret: the ASCII digits 1 through 0, twice.
     *
     * <p>Base32 of "12345678901234567890", which is what this implementation takes.
     */
    private static final String SECRET = base32Of("12345678901234567890");

    @ParameterizedTest(name = "at {0} the code is {1}")
    @CsvSource({
        "59, 287082",
        "1111111109, 081804",
        "1111111111, 050471",
        "1234567890, 005924",
        "2000000000, 279037",
        "20000000000, 353130"
    })
    void producesTheCodesTheSpecificationPublishes(long epochSecond, String expected) {
        assertThat(Totp.codeAt(SECRET, Instant.ofEpochSecond(epochSecond)), equalTo(expected));
    }

    @Test
    void acceptsTheCodeForNow() {
        Instant now = Instant.ofEpochSecond(1111111109);
        assertThat(Totp.matches(SECRET, Totp.codeAt(SECRET, now), now), is(true));
    }

    /**
     * One step either side, because clocks disagree.
     *
     * <p>Thirty seconds ago and thirty seconds ahead are accepted; sixty is not. A phone that is a
     * little out should still let somebody in, and a window wider than that is a replay window
     * nobody asked for.
     */
    @Test
    void acceptsOneStepEitherSideAndNoMore() {
        Instant now = Instant.ofEpochSecond(1111111109);

        assertThat(Totp.matches(SECRET, Totp.codeAt(SECRET, now.minusSeconds(30)), now), is(true));
        assertThat(Totp.matches(SECRET, Totp.codeAt(SECRET, now.plusSeconds(30)), now), is(true));
        assertThat(Totp.matches(SECRET, Totp.codeAt(SECRET, now.minusSeconds(90)), now), is(false));
        assertThat(Totp.matches(SECRET, Totp.codeAt(SECRET, now.plusSeconds(90)), now), is(false));
    }

    @Test
    void refusesAnythingThatIsNotSixDigits() {
        Instant now = Instant.now();

        assertThat(Totp.matches(SECRET, null, now), is(false));
        assertThat(Totp.matches(SECRET, "", now), is(false));
        assertThat(Totp.matches(SECRET, "12345", now), is(false));
        assertThat(Totp.matches(SECRET, "1234567", now), is(false));
    }

    /** Typed off a screen, with the space an app puts in the middle of it. */
    @Test
    void forgivesTheSpaceAnAppShowsTheCodeWith() {
        Instant now = Instant.ofEpochSecond(1111111109);
        String code = Totp.codeAt(SECRET, now);

        assertThat(
                Totp.matches(SECRET, code.substring(0, 3) + " " + code.substring(3), now),
                is(true));
    }

    /**
     * The URI an authenticator is given.
     *
     * <p>The issuer appears in the label and again as a parameter, which is what makes an app that
     * reads either one agree with an app that reads the other.
     */
    @Test
    void buildsAnEnrolmentUriEveryAuthenticatorUnderstands() {
        String uri = Totp.enrolmentUri("Keydra", "ada@example.com", SECRET);

        assertThat(uri, containsString("otpauth://totp/Keydra:ada%40example.com"));
        assertThat(uri, containsString("secret=" + SECRET));
        assertThat(uri, containsString("issuer=Keydra"));
        assertThat(uri, containsString("digits=6"));
        assertThat(uri, containsString("period=30"));
    }

    /** Base32 of an ASCII string, so the RFC's secret can be written as the RFC writes it. */
    private static String base32Of(String ascii) {
        byte[] bytes = ascii.getBytes(StandardCharsets.US_ASCII);
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
        StringBuilder encoded = new StringBuilder();
        int buffer = 0;
        int bits = 0;
        for (byte one : bytes) {
            buffer = (buffer << 8) | (one & 0xFF);
            bits += 8;
            while (bits >= 5) {
                bits -= 5;
                encoded.append(alphabet.charAt((buffer >> bits) & 0x1F));
            }
        }
        if (bits > 0) {
            encoded.append(alphabet.charAt((buffer << (5 - bits)) & 0x1F));
        }
        return encoded.toString();
    }
}
