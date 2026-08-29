package io.keydra.backup.service;

import io.keydra.backup.exception.BackupFailedException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

/**
 * The key pair a backup can be encrypted to.
 *
 * <p>X25519, written as text somebody can paste into a password manager. The public half is
 * configured on a destination and is not a secret; the private half is shown once, kept by whoever
 * asked for it, and never written anywhere by this application — which is the whole claim, and the
 * only way it can be true is by never having it.
 *
 * <p>The prefixes are there so a key pasted into the wrong field says so. {@code keydra-pk1} and
 * {@code keydra-sk1} are impossible to confuse at a glance, which matters when one of them must
 * never be sent anywhere.
 */
public final class BackupKeys {

    public static final String PUBLIC_PREFIX = "keydra-pk1:";
    public static final String PRIVATE_PREFIX = "keydra-sk1:";

    private static final int KEY_BYTES = 32;

    /** How many bytes of the recipient's fingerprint a backup carries. */
    static final int RECIPIENT_ID_BYTES = 8;

    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupKeys() {}

    /** A new pair: what to configure, and what to keep somewhere else. */
    public record Pair(String publicKey, String privateKey) {}

    public static Pair generate() {
        X25519PrivateKeyParameters priv = new X25519PrivateKeyParameters(RANDOM);
        return new Pair(
                PUBLIC_PREFIX + encode(priv.generatePublicKey().getEncoded()),
                PRIVATE_PREFIX + encode(priv.getEncoded()));
    }

    public static X25519PublicKeyParameters publicKey(String text) {
        return new X25519PublicKeyParameters(decode(text, PUBLIC_PREFIX, "public"), 0);
    }

    public static X25519PrivateKeyParameters privateKey(String text) {
        return new X25519PrivateKeyParameters(decode(text, PRIVATE_PREFIX, "private"), 0);
    }

    /** Whether something looks like a public key, for a form that has to say before it saves. */
    public static boolean isPublicKey(String text) {
        try {
            publicKey(text);
            return true;
        } catch (BackupFailedException notOne) {
            return false;
        }
    }

    /**
     * Eight bytes naming a recipient.
     *
     * <p>Carried in a backup's header so a file can answer "this is not for the key you gave me"
     * rather than failing as a tag mismatch, which reads like corruption. Eight bytes of a hash:
     * enough to tell two keys apart, and nothing that helps anybody holding only the id.
     */
    static byte[] recipientId(X25519PublicKeyParameters key) {
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(concat("keydra-recipient:".getBytes(), key.getEncoded()));
            byte[] id = new byte[RECIPIENT_ID_BYTES];
            System.arraycopy(digest, 0, id, 0, RECIPIENT_ID_BYTES);
            return id;
        } catch (Exception impossible) {
            throw new BackupFailedException("Could not name the recipient key", impossible);
        }
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] both = new byte[left.length + right.length];
        System.arraycopy(left, 0, both, 0, left.length);
        System.arraycopy(right, 0, both, left.length, right.length);
        return both;
    }

    private static String encode(byte[] key) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(key);
    }

    private static byte[] decode(String text, String prefix, String half) {
        if (text == null || !text.trim().startsWith(prefix)) {
            throw new BackupFailedException(
                    "That is not a Keydra " + half + " key; one starts with " + prefix);
        }
        byte[] key;
        try {
            key = Base64.getUrlDecoder().decode(text.trim().substring(prefix.length()));
        } catch (IllegalArgumentException malformed) {
            throw new BackupFailedException("That " + half + " key is not readable");
        }
        if (key.length != KEY_BYTES) {
            throw new BackupFailedException(
                    "That " + half + " key is the wrong length for an X25519 key");
        }
        return key;
    }
}
