package io.keydra.authz.service;

import java.security.SecureRandom;

/**
 * RFC 4648 base32, because that is the alphabet an authenticator app expects.
 *
 * <p>Written here rather than taken from a library, and the reason is proportion: it is thirty
 * lines with a published specification and a test that proves it, against a dependency whose
 * maintenance somebody would have to vouch for. The same argument applies to {@link Totp} and not
 * to Argon2, which is thirty lines of nothing anybody should write themselves.
 *
 * <p>No padding. The {@code otpauth://} URI carries the secret unpadded and every authenticator
 * accepts it that way; the {@code =} would only have to be stripped again.
 */
final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private Base32() {}

    /**
     * A new secret, as many bytes as HMAC-SHA1's block is wide.
     *
     * <p>Twenty, which is what RFC 4226 calls the minimum and what every authenticator handles
     * without comment. Longer is not stronger here: HMAC-SHA1 folds a longer key down to the hash's
     * own block size anyway.
     */
    static String newSecret() {
        byte[] bytes = new byte[20];
        RANDOM.nextBytes(bytes);
        return encode(bytes);
    }

    static String encode(byte[] bytes) {
        StringBuilder encoded = new StringBuilder();
        int buffer = 0;
        int bitsHeld = 0;
        for (byte one : bytes) {
            buffer = (buffer << 8) | (one & 0xFF);
            bitsHeld += 8;
            while (bitsHeld >= 5) {
                bitsHeld -= 5;
                encoded.append(ALPHABET.charAt((buffer >> bitsHeld) & 0x1F));
            }
        }
        if (bitsHeld > 0) {
            encoded.append(ALPHABET.charAt((buffer << (5 - bitsHeld)) & 0x1F));
        }
        return encoded.toString();
    }

    /**
     * Reads a secret back.
     *
     * <p>Case-insensitive and forgiving of spaces and padding, because the string may have been
     * typed by somebody reading it off a screen rather than scanning it.
     */
    static byte[] decode(String encoded) {
        String cleaned =
                encoded.replace("=", "").replace(" ", "").toUpperCase(java.util.Locale.ROOT);
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        int buffer = 0;
        int bitsHeld = 0;
        for (char one : cleaned.toCharArray()) {
            int value = ALPHABET.indexOf(one);
            if (value < 0) {
                throw new IllegalArgumentException("Not base32");
            }
            buffer = (buffer << 5) | value;
            bitsHeld += 5;
            if (bitsHeld >= 8) {
                bitsHeld -= 8;
                bytes.write((buffer >> bitsHeld) & 0xFF);
            }
        }
        return bytes.toByteArray();
    }
}
