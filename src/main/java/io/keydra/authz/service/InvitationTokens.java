package io.keydra.authz.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Making the string in an invitation link, and recognising it later.
 *
 * <p>Thirty-two bytes from a secure random source, written the way a URL will carry them. That is
 * enough that guessing is not a threat model, which is what lets the stored form be a plain
 * SHA-256: the hash has to be one-way, and it has to be fast, because it is computed on the request
 * that redeems the link rather than on one somebody is waiting to sign in with.
 *
 * <p>A password gets Argon2 for the opposite reason — it is short, chosen by a person, and worth
 * making expensive to guess. Using it here would be spending half a second per request to protect
 * against an attack that cannot happen.
 */
public final class InvitationTokens {

    /** 256 bits. Long enough that the interesting attacks are elsewhere. */
    private static final int BYTES = 32;

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private InvitationTokens() {}

    /** A fresh token, to be sent once and never stored. */
    public static String issue() {
        byte[] bytes = new byte[BYTES];
        RANDOM.nextBytes(bytes);
        return ENCODER.encodeToString(bytes);
    }

    /** What goes in the database, given what went in the link. */
    public static String fingerprint(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            // Every JVM has SHA-256; this cannot happen, and there is no sensible fallback.
            throw new IllegalStateException("SHA-256 is not available", impossible);
        }
    }
}
