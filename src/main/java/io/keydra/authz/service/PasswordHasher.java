package io.keydra.authz.service;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;

/**
 * Turns a password into something safe to store, and checks one against it.
 *
 * <p>Argon2id, which is what the people who study this recommend for a password nobody has to type
 * more than once a session: it is deliberately slow and deliberately memory-hungry, so an attacker
 * with a stolen table and a rack of GPUs is not much better off than one with a laptop.
 *
 * <p>The parameters are stored in the hash rather than assumed, so raising them later does not
 * invalidate every existing password — an old hash keeps verifying with the settings it was made
 * with, and is rewritten the next time its owner signs in.
 */
@ApplicationScoped
public class PasswordHasher {

    /**
     * How much work each attempt costs.
     *
     * <p>The OWASP recommendation for Argon2id at the time of writing: 19 MiB of memory, two
     * passes, one lane. That is a few tens of milliseconds on the sort of machine Keydra runs on,
     * which nobody notices once a session and an attacker notices a great deal.
     */
    private static final int MEMORY_KIB = 19 * 1024;

    private static final int ITERATIONS = 2;
    private static final int PARALLELISM = 1;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BYTES = 32;

    /** What the stored form starts with, so an old hash can be told from a new scheme later. */
    private static final String PREFIX = "argon2id";

    private final SecureRandom random = new SecureRandom();

    /**
     * The stored form: the scheme, its parameters, the salt and the hash.
     *
     * <p>Everything needed to check a password later, and nothing that helps anybody guess one.
     */
    public String hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = derive(password, salt, MEMORY_KIB, ITERATIONS, PARALLELISM);

        return String.join(
                "$",
                PREFIX,
                String.valueOf(MEMORY_KIB),
                String.valueOf(ITERATIONS),
                String.valueOf(PARALLELISM),
                Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(hash));
    }

    /**
     * Whether this password produces that hash.
     *
     * <p>Compared in constant time. A comparison that returns early on the first wrong byte tells
     * an attacker how much of their guess was right, which turns guessing a hash into guessing it
     * one byte at a time.
     */
    public boolean matches(String password, String stored) {
        if (password == null || stored == null) {
            return false;
        }
        String[] parts = stored.split("\\$");
        if (parts.length != 6 || !PREFIX.equals(parts[0])) {
            return false;
        }
        try {
            int memory = Integer.parseInt(parts[1]);
            int iterations = Integer.parseInt(parts[2]);
            int parallelism = Integer.parseInt(parts[3]);
            byte[] salt = Base64.getDecoder().decode(parts[4]);
            byte[] expected = Base64.getDecoder().decode(parts[5]);

            byte[] actual = derive(password, salt, memory, iterations, parallelism);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException unreadable) {
            // A stored value this cannot parse is not a password that matches; it is a row
            // somebody will have to reset, and saying "no" is the safe half of that.
            return false;
        }
    }

    /** Whether a hash was made with weaker settings than the current ones. */
    public boolean needsRehash(String stored) {
        String[] parts = stored == null ? new String[0] : stored.split("\\$");
        if (parts.length != 6 || !PREFIX.equals(parts[0])) {
            return true;
        }
        try {
            return Integer.parseInt(parts[1]) < MEMORY_KIB
                    || Integer.parseInt(parts[2]) < ITERATIONS;
        } catch (NumberFormatException unreadable) {
            return true;
        }
    }

    private static byte[] derive(
            String password, byte[] salt, int memory, int iterations, int parallelism) {
        Argon2Parameters parameters =
                new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                        .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                        .withSalt(salt)
                        .withMemoryAsKB(memory)
                        .withIterations(iterations)
                        .withParallelism(parallelism)
                        .build();

        Argon2BytesGenerator generator = new Argon2BytesGenerator();
        generator.init(parameters);
        byte[] hash = new byte[HASH_BYTES];
        generator.generateBytes(password.getBytes(StandardCharsets.UTF_8), hash);
        return hash;
    }
}
