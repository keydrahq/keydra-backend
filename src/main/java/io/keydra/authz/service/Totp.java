package io.keydra.authz.service;

import java.nio.ByteBuffer;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The six digits an authenticator app shows, per RFC 6238.
 *
 * <p>Written here rather than taken from a library, for one decisive reason: the RFC publishes test
 * vectors, so a hand-written implementation can be *proved* correct instead of trusted. Thirty
 * lines of HMAC against a dependency nobody in this project would be able to vouch for is a trade
 * that only goes one way. (Argon2 is the opposite case and is a dependency, because a password hash
 * is not thirty lines and getting it subtly wrong is silent.)
 *
 * <p>SHA-1, thirty-second steps, six digits: not a choice so much as the only combination every
 * authenticator handles without being told. The algorithm's weakness against collisions is not what
 * HMAC depends on.
 */
public final class Totp {

    /** How long one code lives, and the unit the counter counts. */
    private static final long STEP_SECONDS = 30;

    private static final int DIGITS = 6;

    /**
     * How many steps either side of now are accepted.
     *
     * <p>One, which is the usual answer and is about clocks rather than about convenience: a phone
     * and a server that disagree by twenty seconds would otherwise refuse every code, and somebody
     * who starts typing at second twenty-nine would be refused by arithmetic. The cost is that a
     * code is good for at most ninety seconds instead of thirty, which is the window a replay would
     * have to fit inside — and a replay needs the code, which needs the phone.
     */
    private static final int DRIFT_STEPS = 1;

    private Totp() {}

    /** The code for a moment, from a base32 secret. */
    public static String codeAt(String base32Secret, Instant at) {
        return code(Base32.decode(base32Secret), at.getEpochSecond() / STEP_SECONDS);
    }

    /**
     * Whether a code is one of the ones this secret is showing.
     *
     * <p>Compared in constant time. The comparison is against six digits somebody typed, so the
     * timing tells an attacker how many leading digits they had right — which turns a million
     * guesses into sixty.
     */
    public static boolean matches(String base32Secret, String candidate, Instant at) {
        if (candidate == null) {
            return false;
        }
        String typed = candidate.replace(" ", "").trim();
        if (typed.length() != DIGITS) {
            return false;
        }
        byte[] secret = Base32.decode(base32Secret);
        long step = at.getEpochSecond() / STEP_SECONDS;
        boolean matched = false;
        for (int drift = -DRIFT_STEPS; drift <= DRIFT_STEPS; drift++) {
            // Every step is checked even after one matches: stopping early would make a code from
            // the previous window answer faster than one from the next.
            matched |= constantTimeEquals(code(secret, step + drift), typed);
        }
        return matched;
    }

    /**
     * What an authenticator is given to set itself up.
     *
     * <p>The issuer appears twice on purpose: once in the label, which is what older apps read, and
     * once as a parameter, which is what current ones read. An app that reads both agrees with
     * itself; an app that reads one is right either way.
     */
    public static String enrolmentUri(String issuer, String account, String base32Secret) {
        String label = encode(issuer) + ":" + encode(account);
        return "otpauth://totp/"
                + label
                + "?secret="
                + base32Secret
                + "&issuer="
                + encode(issuer)
                + "&algorithm=SHA1&digits="
                + DIGITS
                + "&period="
                + STEP_SECONDS;
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * RFC 4226's dynamic truncation, which is the whole of what makes eight bytes into six digits.
     */
    private static String code(byte[] secret, long counter) {
        byte[] mac = hmac(secret, ByteBuffer.allocate(Long.BYTES).putLong(counter).array());
        int offset = mac[mac.length - 1] & 0x0F;
        int binary =
                ((mac[offset] & 0x7F) << 24)
                        | ((mac[offset + 1] & 0xFF) << 16)
                        | ((mac[offset + 2] & 0xFF) << 8)
                        | (mac[offset + 3] & 0xFF);
        return String.format("%0" + DIGITS + "d", binary % (int) Math.pow(10, DIGITS));
    }

    private static byte[] hmac(byte[] secret, byte[] message) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            return mac.doFinal(message);
        } catch (NoSuchAlgorithmException | InvalidKeyException impossible) {
            // HmacSHA1 is required of every JVM, and a key of any length is valid for it.
            throw new IllegalStateException("This JVM cannot compute HMAC-SHA1", impossible);
        }
    }

    private static boolean constantTimeEquals(String left, String right) {
        return java.security.MessageDigest.isEqual(
                left.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                right.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
}
