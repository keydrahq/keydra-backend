package io.keydra.connections.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * Transparently encrypts a column with AES-256-GCM so stored credentials are never at rest in
 * plaintext.
 *
 * <p>The stored form is {@code enc:v2:<id>:<base64(iv || ciphertext || tag)>}, where {@code id}
 * names the key that wrote it. The prefix makes an encrypted value self-describing, so a column
 * written before this converter existed — or restored from a plaintext dump — is passed through
 * instead of being mis-decrypted into garbage.
 *
 * <p>The id is what makes the key rotatable. One key writes and any number can read, so a
 * deployment adds the new key beside the old one, restarts, re-encrypts at its leisure, and only
 * then takes the old one out. Without it a changed secret is every stored credential becoming
 * unreadable at once, which is why nobody would ever change it.
 *
 * <p>{@code enc:v1:} values are what this wrote before there were ids. They are read with the key
 * derived from {@code keydra.crypto.secret} and rewritten as v2 the next time anything saves them —
 * or all at once, by the rotation.
 *
 * <p>Configuration is read through {@link ConfigProvider} rather than CDI injection because
 * Hibernate, not the container, instantiates converters.
 */
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, String> {

    /** The secret that writes. */
    static final String CONFIG_KEY = "keydra.crypto.secret";

    /** Secrets that only read: what a rotation is moving away from. */
    static final String PREVIOUS_KEYS = "keydra.crypto.previous-secrets";

    /** What this wrote before keys had ids. Read, never written. */
    static final String LEGACY_PREFIX = "enc:v1:";

    static final String PREFIX = "enc:v2:";

    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LENGTH = 12;
    private static final int TAG_LENGTH_BITS = 128;

    /**
     * Derives the keys and seeds the generator on first use.
     *
     * <p>An initialisation-on-demand holder rather than double-checked locking: the JVM already
     * guarantees a class is initialised exactly once, so neither needs a volatile field or any
     * synchronisation.
     *
     * <p>The generator lives here rather than on the converter itself for a reason a native build
     * made plain. Hibernate instantiates converters while the image is being built, so a
     * SecureRandom held on the converter is created then — seeded once, at build time, and copied
     * into every running instance of that binary. Every deployment would then produce the same
     * initialisation vectors, which for AES-GCM is not a weakness but a break. Keeping it in a
     * holder that is initialised at startup means it is seeded per process, on the JVM and in a
     * native image alike.
     */
    private static final class KeyHolder {

        /** The key that writes, and the id it stamps on what it wrote. */
        private static final String CURRENT_ID;

        /** Every key that can read, by id. Insertion order is current first. */
        private static final Map<String, SecretKey> KEYS = new LinkedHashMap<>();

        private static final SecureRandom RANDOM = new SecureRandom();

        static {
            String secret = ConfigProvider.getConfig().getValue(CONFIG_KEY, String.class);
            CURRENT_ID = idOf(secret);
            KEYS.put(CURRENT_ID, derive(secret));
            for (String previous : previousSecrets()) {
                KEYS.putIfAbsent(idOf(previous), derive(previous));
            }
        }

        private static List<String> previousSecrets() {
            return ConfigProvider.getConfig()
                    .getOptionalValues(PREVIOUS_KEYS, String.class)
                    .orElseGet(ArrayList::new);
        }

        private static SecretKey derive(String secret) {
            try {
                // SHA-256 turns a secret of any length into the 256 bits AES wants.
                byte[] digest =
                        MessageDigest.getInstance("SHA-256")
                                .digest(secret.getBytes(StandardCharsets.UTF_8));
                return new SecretKeySpec(digest, "AES");
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Unable to derive encryption key", e);
            }
        }

        /**
         * A short name for a key, derived from the key itself.
         *
         * <p>Derived rather than configured so that adding a key is one setting rather than two,
         * and so two deployments given the same secret agree about what it is called. Eight hex
         * characters of a second hash: enough that two keys in one instance will not collide, and
         * nothing that helps anybody who has only the id.
         */
        private static String idOf(String secret) {
            try {
                byte[] digest =
                        MessageDigest.getInstance("SHA-256")
                                .digest(
                                        ("keydra-key-id:" + secret)
                                                .getBytes(StandardCharsets.UTF_8));
                StringBuilder id = new StringBuilder(8);
                for (int i = 0; i < 4; i++) {
                    id.append(String.format("%02x", digest[i]));
                }
                return id.toString();
            } catch (GeneralSecurityException e) {
                throw new IllegalStateException("Unable to name the encryption key", e);
            }
        }
    }

    /** The id of the key that writes, so a rotation can tell what it has already moved. */
    public static String currentKeyId() {
        return KeyHolder.CURRENT_ID;
    }

    /** Whether a stored value was written by the key that writes now. */
    public static boolean isCurrent(String dbData) {
        return dbData != null && dbData.startsWith(PREFIX + KeyHolder.CURRENT_ID + ":");
    }

    /** Whether a stored value is encrypted at all. */
    public static boolean isEncrypted(String dbData) {
        return dbData != null && (dbData.startsWith(PREFIX) || dbData.startsWith(LEGACY_PREFIX));
    }

    @Override
    public String convertToDatabaseColumn(String attribute) {
        if (attribute == null || attribute.isEmpty()) {
            return attribute;
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            KeyHolder.RANDOM.nextBytes(iv);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(
                    Cipher.ENCRYPT_MODE,
                    KeyHolder.KEYS.get(KeyHolder.CURRENT_ID),
                    new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] ciphertext = cipher.doFinal(attribute.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + ciphertext.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(ciphertext, 0, combined, iv.length, ciphertext.length);

            return PREFIX
                    + KeyHolder.CURRENT_ID
                    + ":"
                    + Base64.getEncoder().encodeToString(combined);
        } catch (GeneralSecurityException e) {
            // Deliberately does not include the attribute value in the message.
            throw new IllegalStateException("Unable to encrypt attribute", e);
        }
    }

    @Override
    public String convertToEntityAttribute(String dbData) {
        // An empty value cannot start with either prefix, so the prefix checks cover it.
        if (dbData == null) {
            return null;
        }
        if (dbData.startsWith(PREFIX)) {
            int end = dbData.indexOf(':', PREFIX.length());
            if (end < 0) {
                throw new IllegalStateException("Encrypted value names no key");
            }
            String id = dbData.substring(PREFIX.length(), end);
            SecretKey key = KeyHolder.KEYS.get(id);
            if (key == null) {
                throw new IllegalStateException(
                        "This value was encrypted with key "
                                + id
                                + ", which is not configured. Add it to "
                                + PREVIOUS_KEYS
                                + " to read it.");
            }
            return decrypt(key, dbData.substring(end + 1));
        }
        if (dbData.startsWith(LEGACY_PREFIX)) {
            // Written before keys had ids, so there is only one it can be.
            return decrypt(
                    KeyHolder.KEYS.get(KeyHolder.CURRENT_ID),
                    dbData.substring(LEGACY_PREFIX.length()));
        }
        return dbData;
    }

    private static String decrypt(SecretKey key, String encoded) {
        try {
            byte[] combined = Base64.getDecoder().decode(encoded);
            byte[] iv = new byte[IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, IV_LENGTH);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_LENGTH_BITS, iv));
            byte[] plaintext = cipher.doFinal(combined, IV_LENGTH, combined.length - IV_LENGTH);

            return new String(plaintext, StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Unable to decrypt attribute", e);
        }
    }
}
