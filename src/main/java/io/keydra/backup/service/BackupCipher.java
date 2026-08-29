package io.keydra.backup.service;

import io.keydra.backup.exception.BackupFailedException;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.generators.SCrypt;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;

/**
 * Encrypting a backup so the place it lands cannot read it.
 *
 * <p>A backup is every key and every value, and phase 11's whole point was sending it somewhere
 * else — a bucket somebody else administers, an FTP server the rest of the estate writes to. "The
 * bucket is private" is a sentence about access control, not about what is in the file.
 *
 * <p><b>Framed rather than one stream.</b> A single AES-GCM stream cannot be both memory-bounded
 * and safely readable in Java: {@code CipherInputStream} reports a failed authentication tag as
 * end-of-input, so a truncated or tampered backup would restore as a shorter one that looked
 * complete. Each frame here carries its own tag and its own position, and the file ends with a
 * frame that says it is the last — so truncation, reordering and removal are all refused instead of
 * silently restoring less than was taken.
 *
 * <p><b>The format is written down</b> in docs/DATA-AT-REST.md, and {@code
 * scripts/keydra-decrypt.py} reads it without importing anything from this repository. An encrypted
 * backup only this application can read is not a backup, it is a hostage.
 *
 * <pre>
 * header  "KEYDRA-BACKUP-1\n"   16 bytes
 *         kdf                    1 byte, 1 = scrypt, 2 = one key, 3 = a list of keys
 * kdf 1   N, r, p                3 × int32, big endian
 *         salt                  16 bytes
 *         base nonce             8 bytes
 * kdf 3   count                  1 byte, how many recipients follow
 *  ×count recipient id           8 bytes
 *         ephemeral public      32 bytes
 *         wrapped file key      48 bytes, AES-256-GCM, nonce of zeroes, aad = recipient id
 *         base nonce             8 bytes
 * frames  final                  1 byte, 0 = more follow, 1 = last
 *         length                 int32, ciphertext length including the 16-byte tag
 *         ciphertext             length bytes
 * </pre>
 *
 * <p>Each frame's nonce is the base nonce followed by its index as four big-endian bytes, and its
 * additional authenticated data is that index and the final byte. So a frame cannot be moved, and
 * the last one cannot be made to look like a middle one.
 */
public final class BackupCipher {

    /** What the file starts with, so a reader knows what it is holding before it tries. */
    static final byte[] MAGIC = "KEYDRA-BACKUP-1\n".getBytes(StandardCharsets.US_ASCII);

    /** How many bytes a reader has to look at to know what it is holding. */
    public static final int MAGIC_LENGTH = 16;

    /** A key from a passphrase the server holds. */
    private static final byte KDF_SCRYPT = 1;

    /**
     * A key agreed with the one recipient the server holds the public half of.
     *
     * <p>Read and never written again. Phase 48 replaced it with a list, and a destination with one
     * recipient now writes a list of one — one path through the code and one shape on disk, rather
     * than a branch exercised only when somebody happens to have added a second key. Files written
     * before that stay readable, which is the reason this constant is still here.
     */
    private static final byte KDF_X25519 = 2;

    /**
     * A file key of its own, wrapped once for each recipient.
     *
     * <p>The mode that changes what is being claimed twice over. Against scrypt: the half that
     * decrypts was never on the machine, so neither Keydra nor whoever takes it can read a backup
     * back. Against {@link #KDF_X25519}: the person holding that half is no longer the only person
     * in the world who can, which is what turns "encrypted at rest" back into a backup.
     *
     * <p>A file two people can open cannot derive its stream key from an agreement, because two
     * agreements are two keys and the file is one stream. So the stream gets thirty-two random
     * bytes of its own and each recipient gets those bytes wrapped under the <em>same</em>
     * agreement the single-recipient mode used — the part that is hardest to get right is
     * untouched, and what it protects is thirty-two bytes rather than a stream.
     */
    private static final byte KDF_RECIPIENTS = 3;

    /** How many recipients one file may name, which is what a single count byte allows. */
    public static final int MAX_RECIPIENTS = 255;

    /** What the agreed secret is stretched with, so the same key never appears twice. */
    private static final byte[] HKDF_INFO = "keydra-backup-v1".getBytes(StandardCharsets.US_ASCII);

    /**
     * scrypt, at parameters that cost about 32 MiB and a fraction of a second.
     *
     * <p>Memory-hard, which is what a passphrase needs against hardware that is not a laptop — and
     * in both the library this build already has and Python's standard library, which is what lets
     * the recovery script exist without a dependency for the part that matters.
     */
    private static final int SCRYPT_N = 1 << 15;

    private static final int SCRYPT_R = 8;
    private static final int SCRYPT_P = 1;
    private static final int KEY_BYTES = 32;

    /**
     * How many bytes a wrapped file key takes: the key and its tag.
     *
     * <p>The nonce is zeroes and is not written, which is the one thing here that looks like a
     * mistake. The wrapping key comes from a key pair generated for this file and this recipient
     * and used for nothing else, so there is exactly one message under it — and a counter would be
     * a second thing to get right in exchange for nothing.
     */
    private static final int WRAPPED_KEY_BYTES = KEY_BYTES + 16;

    /** The nonce every wrap uses, for the reason above. */
    private static final byte[] WRAP_NONCE = new byte[12];

    private static final int SALT_BYTES = 16;
    private static final int BASE_NONCE_BYTES = 8;
    private static final int TAG_BITS = 128;

    /** 64 KiB of plaintext per frame: small enough to bound memory, large enough to be cheap. */
    public static final int FRAME_BYTES = 64 * 1024;

    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupCipher() {}

    /** Whether a file begins the way an encrypted backup does. */
    public static boolean looksEncrypted(byte[] firstBytes) {
        return firstBytes != null
                && firstBytes.length >= MAGIC.length
                && Arrays.equals(Arrays.copyOf(firstBytes, MAGIC.length), MAGIC);
    }

    /**
     * Wraps a stream so everything written to it can be read by any one of {@code recipients} and
     * by nobody else.
     *
     * <p>A file key of its own, wrapped once per recipient under an ephemeral pair generated for
     * that recipient and this file. The frames underneath are the ones a passphrase produces, which
     * is why the truncation and tampering this format refuses are refused identically in every
     * mode.
     *
     * <p>Each wrap authenticates the recipient id it sits beside, so a stanza cannot be moved into
     * somebody else's slot — the file would then say a key opens it that does not.
     */
    public static OutputStream encryptTo(OutputStream out, List<String> recipients) {
        if (recipients == null || recipients.isEmpty()) {
            throw new BackupFailedException("A backup encrypted to keys needs at least one key");
        }
        if (recipients.size() > MAX_RECIPIENTS) {
            throw new BackupFailedException(
                    "A backup can name at most " + MAX_RECIPIENTS + " keys");
        }
        List<X25519PublicKeyParameters> to =
                recipients.stream().map(BackupKeys::publicKey).toList();
        try {
            byte[] fileKey = new byte[KEY_BYTES];
            byte[] baseNonce = new byte[BASE_NONCE_BYTES];
            RANDOM.nextBytes(fileKey);
            RANDOM.nextBytes(baseNonce);

            DataOutputStream header = new DataOutputStream(out);
            header.write(MAGIC);
            header.writeByte(KDF_RECIPIENTS);
            header.writeByte(to.size());
            for (X25519PublicKeyParameters recipient : to) {
                X25519PrivateKeyParameters ephemeral = new X25519PrivateKeyParameters(RANDOM);
                X25519PublicKeyParameters ephemeralPublic = ephemeral.generatePublicKey();
                byte[] id = BackupKeys.recipientId(recipient);
                header.write(id);
                header.write(ephemeralPublic.getEncoded());
                header.write(
                        wrap(agree(ephemeral, recipient, ephemeralPublic, recipient), fileKey, id));
            }
            header.write(baseNonce);
            header.flush();

            return new FrameWriter(out, fileKey, baseNonce);
        } catch (IOException unwritable) {
            throw new BackupFailedException("Could not start the encrypted backup", unwritable);
        }
    }

    /** Wraps a stream so everything written to it is encrypted, header first. */
    public static OutputStream encrypt(OutputStream out, String passphrase) {
        try {
            byte[] salt = new byte[SALT_BYTES];
            byte[] baseNonce = new byte[BASE_NONCE_BYTES];
            RANDOM.nextBytes(salt);
            RANDOM.nextBytes(baseNonce);

            DataOutputStream header = new DataOutputStream(out);
            header.write(MAGIC);
            header.writeByte(KDF_SCRYPT);
            header.writeInt(SCRYPT_N);
            header.writeInt(SCRYPT_R);
            header.writeInt(SCRYPT_P);
            header.write(salt);
            header.write(baseNonce);
            header.flush();

            return new FrameWriter(out, deriveKey(passphrase, salt), baseNonce);
        } catch (IOException unwritable) {
            throw new BackupFailedException("Could not start the encrypted backup", unwritable);
        }
    }

    /**
     * Wraps a stream so everything read from it is decrypted, header first.
     *
     * @param secret a passphrase or a private key; which one it has to be is what the file says
     */
    public static InputStream decrypt(InputStream in, String secret) {
        try {
            DataInputStream header = new DataInputStream(in);
            byte[] magic = new byte[MAGIC.length];
            header.readFully(magic);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new BackupFailedException("This is not an encrypted Keydra backup");
            }
            byte kdf = header.readByte();
            if (kdf == KDF_RECIPIENTS) {
                return openForOneOfMany(header, in, secret);
            }
            if (kdf == KDF_X25519) {
                return openForRecipient(header, in, secret);
            }
            if (kdf != KDF_SCRYPT) {
                throw new BackupFailedException(
                        "This backup uses a key derivation this Keydra does not know ("
                                + kdf
                                + ")");
            }
            if (secret == null || secret.isBlank()) {
                throw new BackupFailedException(
                        "This backup was written with a passphrase, and the destination it came"
                                + " from has none configured.");
            }
            if (secret.startsWith(BackupKeys.PRIVATE_PREFIX)) {
                // Said plainly: a private key stretched as a passphrase would fail as a tag
                // mismatch, which reads like a corrupt file.
                throw new BackupFailedException(
                        "This backup was written with a passphrase, not to a key.");
            }
            int n = header.readInt();
            int r = header.readInt();
            int p = header.readInt();
            byte[] salt = new byte[SALT_BYTES];
            byte[] baseNonce = new byte[BASE_NONCE_BYTES];
            header.readFully(salt);
            header.readFully(baseNonce);

            return new FrameReader(in, deriveKey(secret, salt, n, r, p), baseNonce);
        } catch (BackupFailedException already) {
            throw already;
        } catch (IOException unreadable) {
            throw new BackupFailedException("The backup's header could not be read", unreadable);
        }
    }

    /**
     * The list mode: find the stanza this key opens, unwrap the file key, and read the frames.
     *
     * <p>Every stanza is read before any is tried, because the base nonce is behind them and a
     * reader that stopped at the first match would leave the stream in the wrong place.
     */
    private static InputStream openForOneOfMany(
            DataInputStream header, InputStream in, String secret) throws IOException {
        int count = header.readUnsignedByte();
        if (count == 0) {
            throw new BackupFailedException("This backup names no keys at all");
        }
        byte[][] ids = new byte[count][BackupKeys.RECIPIENT_ID_BYTES];
        byte[][] ephemerals = new byte[count][32];
        byte[][] wrapped = new byte[count][WRAPPED_KEY_BYTES];
        for (int stanza = 0; stanza < count; stanza++) {
            header.readFully(ids[stanza]);
            header.readFully(ephemerals[stanza]);
            header.readFully(wrapped[stanza]);
        }
        byte[] baseNonce = new byte[BASE_NONCE_BYTES];
        header.readFully(baseNonce);

        if (secret == null || secret.isBlank()) {
            throw new BackupFailedException(
                    "This backup was encrypted to a key. Supply the private half to read it.");
        }
        X25519PrivateKeyParameters mine = BackupKeys.privateKey(secret);
        X25519PublicKeyParameters minePublic = mine.generatePublicKey();
        byte[] mineId = BackupKeys.recipientId(minePublic);

        for (int stanza = 0; stanza < count; stanza++) {
            if (!Arrays.equals(ids[stanza], mineId)) {
                continue;
            }
            X25519PublicKeyParameters ephemeralPublic =
                    new X25519PublicKeyParameters(ephemerals[stanza], 0);
            byte[] fileKey =
                    unwrap(
                            agree(mine, ephemeralPublic, ephemeralPublic, minePublic),
                            wrapped[stanza],
                            ids[stanza]);
            return new FrameReader(in, fileKey, baseNonce);
        }
        // Said plainly rather than left to fail as a tag mismatch, which reads like a corrupt
        // file and sends somebody looking for the wrong problem. The number is worth saying:
        // "encrypted to three keys and yours is not one" is a different morning from "encrypted
        // to one key and yours is not it".
        throw new BackupFailedException(
                "This backup was encrypted to "
                        + count
                        + (count == 1 ? " key" : " keys")
                        + ", and the one supplied is not among them.");
    }

    /** Seals the file key for one recipient. */
    private static byte[] wrap(byte[] wrapping, byte[] fileKey, byte[] recipientId) {
        try {
            return cipher(Cipher.ENCRYPT_MODE, wrapping, WRAP_NONCE, recipientId).doFinal(fileKey);
        } catch (GeneralSecurityException impossible) {
            throw new BackupFailedException("Could not seal the backup's key", impossible);
        } finally {
            Arrays.fill(wrapping, (byte) 0);
        }
    }

    /** Opens it again, or says the key does not fit rather than half-opening anything. */
    private static byte[] unwrap(byte[] wrapping, byte[] sealed, byte[] recipientId) {
        try {
            return cipher(Cipher.DECRYPT_MODE, wrapping, WRAP_NONCE, recipientId).doFinal(sealed);
        } catch (GeneralSecurityException wrongKey) {
            throw new BackupFailedException(
                    "This backup names that key, and the key does not open it. The file has been"
                            + " altered.",
                    wrongKey);
        } finally {
            Arrays.fill(wrapping, (byte) 0);
        }
    }

    /** The recipient half: agree with the ephemeral key the file carries. */
    private static InputStream openForRecipient(
            DataInputStream header, InputStream in, String secret) throws IOException {
        byte[] recipientId = new byte[BackupKeys.RECIPIENT_ID_BYTES];
        byte[] ephemeralPublic = new byte[32];
        byte[] baseNonce = new byte[BASE_NONCE_BYTES];
        header.readFully(recipientId);
        header.readFully(ephemeralPublic);
        header.readFully(baseNonce);

        if (secret == null || secret.isBlank()) {
            throw new BackupFailedException(
                    "This backup was encrypted to a key. Supply the private half to read it.");
        }
        X25519PrivateKeyParameters mine = BackupKeys.privateKey(secret);
        X25519PublicKeyParameters minePublic = mine.generatePublicKey();
        if (!Arrays.equals(BackupKeys.recipientId(minePublic), recipientId)) {
            // Said plainly rather than left to fail as a tag mismatch, which reads like a
            // corrupt file and sends somebody looking for the wrong problem.
            throw new BackupFailedException(
                    "This backup was encrypted to a different key than the one supplied.");
        }
        return new FrameReader(
                in,
                agree(
                        mine,
                        new X25519PublicKeyParameters(ephemeralPublic, 0),
                        new X25519PublicKeyParameters(ephemeralPublic, 0),
                        minePublic),
                baseNonce);
    }

    /**
     * The symmetric key both halves arrive at.
     *
     * <p>X25519 then HKDF-SHA256, salted with both public keys so the same pair of keys used twice
     * cannot produce the same stream, and labelled so this key is only ever this.
     */
    private static byte[] agree(
            X25519PrivateKeyParameters mine,
            X25519PublicKeyParameters theirs,
            X25519PublicKeyParameters ephemeralPublic,
            X25519PublicKeyParameters recipientPublic) {
        byte[] shared = new byte[32];
        X25519Agreement agreement = new X25519Agreement();
        agreement.init(mine);
        agreement.calculateAgreement(theirs, shared, 0);

        byte[] salt = new byte[64];
        System.arraycopy(ephemeralPublic.getEncoded(), 0, salt, 0, 32);
        System.arraycopy(recipientPublic.getEncoded(), 0, salt, 32, 32);

        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        hkdf.init(new HKDFParameters(shared, salt, HKDF_INFO));
        byte[] key = new byte[KEY_BYTES];
        hkdf.generateBytes(key, 0, KEY_BYTES);
        Arrays.fill(shared, (byte) 0);
        return key;
    }

    private static byte[] deriveKey(String passphrase, byte[] salt) {
        return deriveKey(passphrase, salt, SCRYPT_N, SCRYPT_R, SCRYPT_P);
    }

    private static byte[] deriveKey(String passphrase, byte[] salt, int n, int r, int p) {
        return SCrypt.generate(
                passphrase.getBytes(StandardCharsets.UTF_8), salt, n, r, p, KEY_BYTES);
    }

    private static byte[] nonceFor(byte[] base, int frame) {
        byte[] nonce = Arrays.copyOf(base, BASE_NONCE_BYTES + 4);
        nonce[BASE_NONCE_BYTES] = (byte) (frame >>> 24);
        nonce[BASE_NONCE_BYTES + 1] = (byte) (frame >>> 16);
        nonce[BASE_NONCE_BYTES + 2] = (byte) (frame >>> 8);
        nonce[BASE_NONCE_BYTES + 3] = (byte) frame;
        return nonce;
    }

    /** A frame's index and whether it is the last, authenticated but not encrypted. */
    private static byte[] aad(int frame, boolean last) {
        return new byte[] {
            (byte) (frame >>> 24),
            (byte) (frame >>> 16),
            (byte) (frame >>> 8),
            (byte) frame,
            (byte) (last ? 1 : 0)
        };
    }

    private static Cipher cipher(int mode, byte[] key, byte[] nonce, byte[] aad) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(aad);
            return cipher;
        } catch (GeneralSecurityException impossible) {
            throw new BackupFailedException("Could not set up the cipher", impossible);
        }
    }

    /** Buffers plaintext and writes one authenticated frame at a time. */
    private static final class FrameWriter extends OutputStream {

        private final OutputStream out;
        private final byte[] key;
        private final byte[] baseNonce;
        private final byte[] buffer = new byte[FRAME_BYTES];
        private int held;
        private int frame;
        private boolean closed;

        FrameWriter(OutputStream out, byte[] key, byte[] baseNonce) {
            this.out = out;
            this.key = key;
            this.baseNonce = baseNonce;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            int written = 0;
            while (written < length) {
                int room = Math.min(FRAME_BYTES - held, length - written);
                System.arraycopy(bytes, offset + written, buffer, held, room);
                held += room;
                written += room;
                if (held == FRAME_BYTES) {
                    emit(false);
                }
            }
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            // Always a final frame, even for an empty backup: it is what says the file ends
            // here rather than having been cut short.
            emit(true);
            out.close();
        }

        private void emit(boolean last) throws IOException {
            byte[] sealed;
            try {
                sealed =
                        cipher(
                                        Cipher.ENCRYPT_MODE,
                                        key,
                                        nonceFor(baseNonce, frame),
                                        aad(frame, last))
                                .doFinal(buffer, 0, held);
            } catch (GeneralSecurityException impossible) {
                throw new IOException("Could not encrypt a backup frame", impossible);
            }
            DataOutputStream data = new DataOutputStream(out);
            data.writeByte(last ? 1 : 0);
            data.writeInt(sealed.length);
            data.write(sealed);
            data.flush();
            held = 0;
            frame++;
        }
    }

    /** Reads one authenticated frame at a time, and refuses a file that stops early. */
    private static final class FrameReader extends InputStream {

        private final DataInputStream in;
        private final byte[] key;
        private final byte[] baseNonce;
        private byte[] plain = new byte[0];
        private int offset;
        private int frame;
        private boolean finished;

        FrameReader(InputStream in, byte[] key, byte[] baseNonce) {
            this.in = new DataInputStream(in);
            this.key = key;
            this.baseNonce = baseNonce;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            return read(one, 0, 1) < 0 ? -1 : one[0] & 0xff;
        }

        @Override
        public int read(byte[] bytes, int off, int length) throws IOException {
            if (offset >= plain.length && !fill()) {
                return -1;
            }
            int taken = Math.min(length, plain.length - offset);
            System.arraycopy(plain, offset, bytes, off, taken);
            offset += taken;
            return taken;
        }

        @Override
        public void close() throws IOException {
            in.close();
        }

        /** Reads the next frame, or answers false once the file has properly ended. */
        private boolean fill() throws IOException {
            while (true) {
                if (finished) {
                    return false;
                }
                boolean last;
                int length;
                try {
                    last = in.readByte() == 1;
                    length = in.readInt();
                } catch (IOException endOfFile) {
                    // Nothing said this was the end, so something removed it.
                    throw new BackupFailedException(
                            "This backup stops in the middle. It was truncated in transit or"
                                    + " where it was stored.");
                }
                if (length < 0 || length > FRAME_BYTES + 64) {
                    throw new BackupFailedException("This backup's frames are not readable");
                }
                byte[] sealed = new byte[length];
                try {
                    in.readFully(sealed);
                } catch (IOException endOfFile) {
                    // A frame that announced its length and then ran out: the same failure as
                    // above, one step later.
                    throw new BackupFailedException(
                            "This backup stops in the middle. It was truncated in transit or"
                                    + " where it was stored.");
                }
                try {
                    plain =
                            cipher(
                                            Cipher.DECRYPT_MODE,
                                            key,
                                            nonceFor(baseNonce, frame),
                                            aad(frame, last))
                                    .doFinal(sealed);
                } catch (GeneralSecurityException wrong) {
                    throw new BackupFailedException(
                            "This backup could not be decrypted. Either the passphrase is not the"
                                    + " one it was written with, or the file has been altered.");
                }
                offset = 0;
                frame++;
                finished = last;
                if (plain.length > 0) {
                    return true;
                }
                if (finished) {
                    return false;
                }
            }
        }
    }
}
