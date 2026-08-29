package io.keydra.backup;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.keydra.backup.exception.BackupFailedException;
import io.keydra.backup.service.BackupCipher;
import io.keydra.backup.service.BackupKeys;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.junit.jupiter.api.Test;

/**
 * A backup the place it lands cannot read.
 *
 * <p>The tests worth having here are the ones about the failures, not the round trip. A cipher that
 * encrypts and decrypts is easy; one that notices a file was cut in half is the reason this is
 * framed rather than a single stream.
 */
class BackupCipherTest {

    private static final String PASSPHRASE = "a-passphrase-nobody-guesses";

    @Test
    void whatGoesInComesBackOut() {
        byte[] original = payload(200_000);

        assertThat(Arrays.equals(roundTrip(original, PASSPHRASE), original), equalTo(true));
    }

    @Test
    void survivesBeingEmpty() {
        // A backup of a keyspace with nothing in it is still a backup, and still has to end
        // with the frame that says so.
        assertThat(roundTrip(new byte[0], PASSPHRASE).length, equalTo(0));
    }

    @Test
    void theFileDoesNotContainWhatWasWritten() {
        byte[] secret = "session:token very-secret-value".getBytes(StandardCharsets.UTF_8);

        String written = new String(sealed(secret, PASSPHRASE), StandardCharsets.ISO_8859_1);

        assertThat(written, not(containsString("very-secret-value")));
        // It does say what it is, so a reader knows before it tries.
        assertThat(written, containsString("KEYDRA-BACKUP-1"));
    }

    @Test
    void theWrongPassphraseIsRefusedRatherThanGuessedAt() {
        byte[] file = sealed(payload(1000), PASSPHRASE);

        BackupFailedException refused =
                assertThrows(
                        BackupFailedException.class,
                        () -> read(BackupCipher.decrypt(new ByteArrayInputStream(file), "wrong")));

        assertThat(refused.getMessage(), containsString("passphrase"));
    }

    @Test
    void aTruncatedBackupIsRefusedRatherThanRestoredShort() {
        // The failure this format exists for. A single GCM stream through Java's
        // CipherInputStream reports a failed tag as end-of-input, so half a backup would
        // restore as a whole one and nobody would find out until they needed the rest.
        byte[] file = sealed(payload(200_000), PASSPHRASE);
        byte[] half = Arrays.copyOf(file, file.length / 2);

        BackupFailedException refused =
                assertThrows(
                        BackupFailedException.class,
                        () ->
                                read(
                                        BackupCipher.decrypt(
                                                new ByteArrayInputStream(half), PASSPHRASE)));

        assertThat(refused.getMessage(), containsString("stops in the middle"));
    }

    @Test
    void aBackupMissingItsLastFrameIsRefused() {
        // Exactly one frame removed from the end, which is the shape a partial upload takes.
        byte[] file = sealed(payload(BackupCipher.FRAME_BYTES * 2 + 10), PASSPHRASE);
        byte[] shortened = Arrays.copyOf(file, file.length - 40);

        assertThrows(
                BackupFailedException.class,
                () -> read(BackupCipher.decrypt(new ByteArrayInputStream(shortened), PASSPHRASE)));
    }

    @Test
    void aByteChangedAnywhereIsRefused() {
        byte[] file = sealed(payload(50_000), PASSPHRASE);
        // Well past the header, so this is a change to the ciphertext itself.
        file[file.length / 2] ^= 0x01;

        assertThrows(
                BackupFailedException.class,
                () -> read(BackupCipher.decrypt(new ByteArrayInputStream(file), PASSPHRASE)));
    }

    @Test
    void somethingThatIsNotABackupIsSaidToNotBeOne() {
        byte[] nonsense =
                "just some bytes that are not a backup at all".getBytes(StandardCharsets.UTF_8);

        BackupFailedException refused =
                assertThrows(
                        BackupFailedException.class,
                        () -> BackupCipher.decrypt(new ByteArrayInputStream(nonsense), PASSPHRASE));

        assertThat(refused.getMessage(), containsString("not an encrypted Keydra backup"));
    }

    @Test
    void aFileCanBeRecognisedWithoutBeingOpened() {
        assertThat(BackupCipher.looksEncrypted(sealed(payload(10), PASSPHRASE)), equalTo(true));
        assertThat(
                BackupCipher.looksEncrypted("plain ndjson".getBytes(StandardCharsets.UTF_8)),
                equalTo(false));
    }

    // --- Encrypted to a key the server does not hold ------------------------

    @Test
    void whatIsEncryptedToAKeyComesBackWithTheOtherHalf() {
        BackupKeys.Pair pair = BackupKeys.generate();
        byte[] original = payload(100_000);

        byte[] file = sealedTo(original, pair.publicKey());

        assertThat(
                Arrays.equals(
                        read(
                                BackupCipher.decrypt(
                                        new ByteArrayInputStream(file), pair.privateKey())),
                        original),
                equalTo(true));
    }

    @Test
    void thePublicHalfCannotOpenWhatItSealed() {
        // The whole claim. Keydra holds this half and only this half, so an instance that
        // wrote a backup every night cannot read one back — and neither can whoever takes it.
        BackupKeys.Pair pair = BackupKeys.generate();
        byte[] file = sealedTo(payload(1000), pair.publicKey());

        assertThrows(
                BackupFailedException.class,
                () -> read(BackupCipher.decrypt(new ByteArrayInputStream(file), pair.publicKey())));
    }

    @Test
    void anotherKeyIsToldItIsAnotherKey() {
        BackupKeys.Pair recipient = BackupKeys.generate();
        BackupKeys.Pair somebodyElse = BackupKeys.generate();
        byte[] file = sealedTo(payload(1000), recipient.publicKey());

        BackupFailedException refused =
                assertThrows(
                        BackupFailedException.class,
                        () ->
                                read(
                                        BackupCipher.decrypt(
                                                new ByteArrayInputStream(file),
                                                somebodyElse.privateKey())));

        // Named rather than left to fail as a tag mismatch, which reads like a corrupt file
        // and sends somebody looking for the wrong problem. Since phase 48 the sentence also
        // says how many keys the file does name, because one and five are different mornings.
        assertThat(refused.getMessage(), containsString("not among them"));
    }

    @Test
    void aBackupWithNoKeySuppliedSaysWhatItNeeds() {
        BackupKeys.Pair pair = BackupKeys.generate();
        byte[] file = sealedTo(payload(1000), pair.publicKey());

        BackupFailedException refused =
                assertThrows(
                        BackupFailedException.class,
                        () -> read(BackupCipher.decrypt(new ByteArrayInputStream(file), "")));

        assertThat(refused.getMessage(), containsString("private half"));
    }

    @Test
    void theTwoModesDoNotGetConfusedForEachOther() {
        BackupKeys.Pair pair = BackupKeys.generate();

        // A private key offered to a file written with a passphrase.
        byte[] withPassphrase = sealed(payload(100), PASSPHRASE);
        assertThat(
                assertThrows(
                                BackupFailedException.class,
                                () ->
                                        read(
                                                BackupCipher.decrypt(
                                                        new ByteArrayInputStream(withPassphrase),
                                                        pair.privateKey())))
                        .getMessage(),
                containsString("passphrase, not to a key"));

        // And a passphrase offered to a file written to a key.
        byte[] toAKey = sealedTo(payload(100), pair.publicKey());
        assertThrows(
                BackupFailedException.class,
                () -> read(BackupCipher.decrypt(new ByteArrayInputStream(toAKey), PASSPHRASE)));
    }

    @Test
    void aTruncatedBackupIsRefusedInThisModeToo() {
        // The frames are the same in both modes, which is the point of only the header
        // differing — so the failure this format exists for is refused identically.
        BackupKeys.Pair pair = BackupKeys.generate();
        byte[] file = sealedTo(payload(200_000), pair.publicKey());
        byte[] half = Arrays.copyOf(file, file.length / 2);

        assertThat(
                assertThrows(
                                BackupFailedException.class,
                                () ->
                                        read(
                                                BackupCipher.decrypt(
                                                        new ByteArrayInputStream(half),
                                                        pair.privateKey())))
                        .getMessage(),
                containsString("stops in the middle"));
    }

    @Test
    void aKeyPastedIntoTheWrongFieldSaysSo() {
        BackupKeys.Pair pair = BackupKeys.generate();

        assertThat(BackupKeys.isPublicKey(pair.publicKey()), equalTo(true));
        assertThat(BackupKeys.isPublicKey(pair.privateKey()), equalTo(false));
        assertThat(BackupKeys.isPublicKey("keydra-pk1:not-a-key"), equalTo(false));
    }

    @Test
    void twoBackupsToOneKeyShareNoBytes() {
        // A fresh ephemeral pair per backup, so nothing about one file says anything about
        // another written to the same recipient.
        BackupKeys.Pair pair = BackupKeys.generate();
        byte[] same = payload(4096);

        byte[] first = sealedTo(same, pair.publicKey());
        byte[] second = sealedTo(same, pair.publicKey());

        assertThat(Arrays.equals(first, second), equalTo(false));
    }

    // --- More than one person can open it -----------------------------------

    /**
     * The whole point of the phase: two keys, one file, and either half opens it.
     *
     * <p>One recipient meant the person holding that private half was the only person in the world
     * who could read a year of backups — and the fix everybody reaches for first, sharing the
     * private half, is worse than the problem.
     */
    @Test
    void eitherOfTwoKeysOpensTheSameFile() {
        BackupKeys.Pair ada = BackupKeys.generate();
        BackupKeys.Pair theSafe = BackupKeys.generate();
        byte[] plaintext = payload(9000);

        byte[] file = sealedTo(plaintext, ada.publicKey(), theSafe.publicKey());

        assertThat(
                read(BackupCipher.decrypt(new ByteArrayInputStream(file), ada.privateKey())),
                equalTo(plaintext));
        assertThat(
                read(BackupCipher.decrypt(new ByteArrayInputStream(file), theSafe.privateKey())),
                equalTo(plaintext));
    }

    /** And a third key opens nothing, which is the other half of the same claim. */
    @Test
    void aKeyThatIsNotNamedOpensNothing() {
        BackupKeys.Pair ada = BackupKeys.generate();
        BackupKeys.Pair theSafe = BackupKeys.generate();
        BackupKeys.Pair somebodyElse = BackupKeys.generate();
        byte[] file = sealedTo(payload(64), ada.publicKey(), theSafe.publicKey());

        BackupFailedException refused =
                assertThrows(
                        BackupFailedException.class,
                        () ->
                                read(
                                        BackupCipher.decrypt(
                                                new ByteArrayInputStream(file),
                                                somebodyElse.privateKey())));

        // The count is worth saying: "encrypted to two keys and yours is not one" is a different
        // morning from "encrypted to one key and yours is not it".
        assertThat(refused.getMessage(), containsString("2 keys"));
    }

    /** A destination with one key writes a list of one, rather than the old shape. */
    @Test
    void oneRecipientIsAListOfOne() {
        BackupKeys.Pair only = BackupKeys.generate();
        byte[] plaintext = payload(2048);

        byte[] file = sealedTo(plaintext, only.publicKey());

        assertThat(file[BackupCipher.MAGIC_LENGTH], equalTo((byte) 3));
        assertThat(
                read(BackupCipher.decrypt(new ByteArrayInputStream(file), only.privateKey())),
                equalTo(plaintext));
    }

    /**
     * A file written before this phase still opens, which is not negotiable.
     *
     * <p>Every encrypted backup already in a bucket was written the old way, and a format change
     * that could not read them would have turned the thing this feature exists to protect into the
     * thing it destroyed. Written by hand here rather than by the application, because the
     * application no longer writes it.
     */
    @Test
    void aFileWrittenTheOldWayStillOpens() {
        BackupKeys.Pair pair = BackupKeys.generate();
        byte[] plaintext = payload(5000);

        byte[] file = sealedTheOldWay(plaintext, pair.publicKey());

        assertThat(file[BackupCipher.MAGIC_LENGTH], equalTo((byte) 2));
        assertThat(
                read(BackupCipher.decrypt(new ByteArrayInputStream(file), pair.privateKey())),
                equalTo(plaintext));
    }

    /** Nothing to encrypt to is a refusal rather than a file nobody can read. */
    @Test
    void aBackupToNobodyIsRefused() {
        assertThrows(
                BackupFailedException.class,
                () -> BackupCipher.encryptTo(new ByteArrayOutputStream(), java.util.List.of()));
    }

    // --- Helpers -----------------------------------------------------------

    private static byte[] payload(int length) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < length; i++) {
            bytes[i] = (byte) ('a' + (i % 26));
        }
        return bytes;
    }

    private static byte[] sealedTo(byte[] plaintext, String... recipients) {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        try (OutputStream out = BackupCipher.encryptTo(file, java.util.List.of(recipients))) {
            out.write(plaintext);
        } catch (Exception unwritable) {
            throw new IllegalStateException(unwritable);
        }
        return file.toByteArray();
    }

    /**
     * A single-recipient file, written the way phase 14 wrote them.
     *
     * <p>By hand rather than by the application, because the application does not write this any
     * more — and writing it here from the format as documented makes the test the stronger one: it
     * proves an independent implementation's file opens, rather than proving a round trip through
     * our own code.
     */
    private static byte[] sealedTheOldWay(byte[] plaintext, String recipient) {
        try {
            X25519PublicKeyParameters to = BackupKeys.publicKey(recipient);
            X25519PrivateKeyParameters ephemeral =
                    new X25519PrivateKeyParameters(new java.security.SecureRandom());
            X25519PublicKeyParameters ephemeralPublic = ephemeral.generatePublicKey();

            byte[] shared = new byte[32];
            X25519Agreement agreement = new X25519Agreement();
            agreement.init(ephemeral);
            agreement.calculateAgreement(to, shared, 0);

            byte[] salt = new byte[64];
            System.arraycopy(ephemeralPublic.getEncoded(), 0, salt, 0, 32);
            System.arraycopy(to.getEncoded(), 0, salt, 32, 32);
            HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
            hkdf.init(
                    new HKDFParameters(
                            shared, salt, "keydra-backup-v1".getBytes(StandardCharsets.US_ASCII)));
            byte[] key = new byte[32];
            hkdf.generateBytes(key, 0, 32);

            byte[] id =
                    Arrays.copyOf(
                            java.security.MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            concat(
                                                    "keydra-recipient:"
                                                            .getBytes(StandardCharsets.US_ASCII),
                                                    to.getEncoded())),
                            8);
            byte[] baseNonce = new byte[8];
            new java.security.SecureRandom().nextBytes(baseNonce);

            ByteArrayOutputStream file = new ByteArrayOutputStream();
            file.write("KEYDRA-BACKUP-1\n".getBytes(StandardCharsets.US_ASCII));
            file.write(2);
            file.write(id);
            file.write(ephemeralPublic.getEncoded());
            file.write(baseNonce);

            // One frame, which is what anything under 64 KiB produces.
            javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
            byte[] nonce = Arrays.copyOf(baseNonce, 12);
            cipher.init(
                    javax.crypto.Cipher.ENCRYPT_MODE,
                    new javax.crypto.spec.SecretKeySpec(key, "AES"),
                    new javax.crypto.spec.GCMParameterSpec(128, nonce));
            cipher.updateAAD(new byte[] {0, 0, 0, 0, 1});
            byte[] sealed = cipher.doFinal(plaintext);

            file.write(1);
            file.write(sealed.length >>> 24);
            file.write(sealed.length >>> 16);
            file.write(sealed.length >>> 8);
            file.write(sealed.length);
            file.write(sealed);
            return file.toByteArray();
        } catch (Exception impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static byte[] concat(byte[] left, byte[] right) {
        byte[] both = new byte[left.length + right.length];
        System.arraycopy(left, 0, both, 0, left.length);
        System.arraycopy(right, 0, both, left.length, right.length);
        return both;
    }

    private static byte[] sealed(byte[] plaintext, String passphrase) {
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        try (OutputStream out = BackupCipher.encrypt(file, passphrase)) {
            out.write(plaintext);
        } catch (Exception unwritable) {
            throw new IllegalStateException(unwritable);
        }
        return file.toByteArray();
    }

    private static byte[] roundTrip(byte[] plaintext, String passphrase) {
        return read(
                BackupCipher.decrypt(
                        new ByteArrayInputStream(sealed(plaintext, passphrase)), passphrase));
    }

    private static byte[] read(InputStream in) {
        try (InputStream open = in) {
            return open.readAllBytes();
        } catch (java.io.IOException unreadable) {
            // The frame reader reports its own refusals as BackupFailedException, which is a
            // RuntimeException and therefore comes past this.
            throw new IllegalStateException(unreadable);
        }
    }
}
