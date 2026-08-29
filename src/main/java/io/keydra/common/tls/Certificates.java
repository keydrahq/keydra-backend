package io.keydra.common.tls;

import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMEncryptedKeyPair;
import org.bouncycastle.openssl.PEMKeyPair;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.openssl.jcajce.JcePEMDecryptorProviderBuilder;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;

/**
 * PEM text, read once so that a bad one is refused where somebody can fix it.
 *
 * <p>A certificate that does not parse is otherwise a connection that fails at three in the morning
 * with a message about handshakes — which names neither the profile nor the field. This is called
 * when a profile is saved, so the refusal arrives while the form is still open.
 *
 * <p>In {@code common} because what it does is the part every engine shares. What each engine then
 * needs is different — Vert.x takes PEM buffers, Aerospike takes a built {@code SSLContext}, TiKV
 * takes paths on disk. Turning the text into something usable is written once; what a client wants
 * of it is adapted here, one method per shape.
 */
public final class Certificates {

    /** What a PEM block looks like, whatever it holds. */
    private static final Pattern BLOCK =
            Pattern.compile(
                    "-----BEGIN ([A-Z0-9 ]+)-----\\s*([A-Za-z0-9+/=\\s]+?)\\s*-----END \\1-----");

    /** How many a field may hold, so a chain is allowed and a pasted file is not a denial. */
    private static final int MOST_BLOCKS = 16;

    private Certificates() {}

    /** Why a certificate or a key was not accepted, in a sentence a form can show. */
    public static class NotUsableException extends RuntimeException {
        public NotUsableException(String message) {
            super(message);
        }
    }

    /**
     * Whether there is anything here at all, which is what "leave it alone" is distinguished from.
     */
    public static boolean present(String pem) {
        return pem != null && !pem.isBlank();
    }

    /**
     * Reads a certificate, or a chain of them, and refuses anything that is not one.
     *
     * <p>Parsed with the JDK's own {@code CertificateFactory} rather than by looking at the text: a
     * base64 block between the right two lines can still be a key, a request, or a file somebody
     * truncated, and each of those reaches the handshake as a different confusing failure.
     */
    public static void requireCertificate(String pem, String what) {
        String[] blocks = blocksOf(pem, what);
        for (String block : blocks) {
            byte[] der = decode(block, what);
            try {
                CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(der));
            } catch (Exception notOne) {
                throw new NotUsableException(
                        what
                                + " is not a certificate. Paste the PEM, beginning -----BEGIN"
                                + " CERTIFICATE-----.");
            }
        }
    }

    /**
     * Reads a private key, with its passphrase where it has one, and says which way it is wrong.
     *
     * <p>Parsed rather than pattern-matched, by the same reader that later turns it into something
     * a client can use. A check that was stricter than the parser is a check that refuses keys
     * which work: an EC key normally arrives with an {@code EC PARAMETERS} block in front of it,
     * and a rule counting blocks would call that two keys.
     *
     * <p>Three ways of being wrong, and each has a different thing to do about it — which is the
     * whole reason for checking here rather than letting the handshake do it, where they are one
     * failure with one unhelpful message. Locked with nothing to open it; opened with the wrong
     * thing; and a passphrase supplied for a key that is not locked at all, which is refused rather
     * than ignored because ignoring it leaves somebody believing a key is protected when it is not.
     */
    public static void requirePrivateKey(String pem, String passphrase, String what) {
        if (!present(pem)) {
            throw new NotUsableException(what + " is empty.");
        }
        if (present(passphrase) && !isLocked(pem)) {
            throw new NotUsableException(
                    what
                            + " is not protected by a passphrase, so the passphrase would never be"
                            + " used. Clear it, or paste the protected key.");
        }
        try {
            privateKeyOf(pem, passphrase);
        } catch (NotUsableException notUsable) {
            if (isLocked(pem)) {
                // Locked, so what went wrong is about the passphrase rather than about the key —
                // "this key could not be read" is true of a good key and a mistyped password, and
                // it sends whoever reads it to look at the wrong one of the two.
                throw new NotUsableException(
                        present(passphrase)
                                ? what + "'s passphrase does not open it."
                                : what
                                        + " is protected by a passphrase. Supply it in the"
                                        + " passphrase field.");
            }
            throw new NotUsableException(
                    what
                            + " is not a private key. Paste the PEM, beginning -----BEGIN PRIVATE"
                            + " KEY----- or -----BEGIN RSA PRIVATE KEY-----.");
        }
    }

    /**
     * Whether this PEM holds a key that needs opening, read off the text.
     *
     * <p>The two formats announce themselves differently. An encrypted PKCS#8 key says {@code
     * ENCRYPTED PRIVATE KEY} in its header; the older format says nothing there at all and marks
     * itself with {@code Proc-Type: 4,ENCRYPTED} on the line after — which is not base64, so a
     * reader looking for PEM shape reports that the file is not PEM. It is; it is locked.
     *
     * <p>Used only to choose which sentence to show. What actually opens a key is the parser, and a
     * text check that disagreed with it would only ever change the wording of a refusal.
     */
    private static boolean isLocked(String pem) {
        return pem != null && (pem.contains("ENCRYPTED PRIVATE KEY") || pem.contains("Proc-Type:"));
    }

    /**
     * The key, unlocked, in the one shape everything here accepts.
     *
     * <p>This is where a passphrase stops. Vert.x's PEM options have no field for one and
     * Aerospike's policy has no field for one, and neither needs it: what they are handed is an
     * ordinary unencrypted key, so neither has a code path for this and neither can behave
     * differently from the other about it.
     *
     * <p>It runs for every key rather than only the locked ones, and that is deliberate. A PKCS#1
     * key going to Vert.x and a PKCS#8 one going to Aerospike would otherwise take two roads to the
     * same handshake, and a key that works on one kind of target and not another is the class of
     * difference phase 54 exists to design out. One road is how it stops being possible.
     *
     * <p>In memory, built when a client is, and never written anywhere.
     */
    public static String unlockedPrivateKey(String pem, String passphrase) {
        PrivateKey key = privateKeyOf(pem, passphrase);
        return "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(key.getEncoded())
                + "\n-----END PRIVATE KEY-----\n";
    }

    /**
     * The same material, as the {@code SSLContext} a client without PEM support wants.
     *
     * <p>Aerospike's TLS policy takes one of these and nothing else, so this is the adapter rather
     * than a second way of configuring the same thing: the text is the same text, read by the same
     * methods above, and what differs is only the object handed to the client.
     *
     * <p>Parsed with BouncyCastle rather than by hand, so that a PKCS#1 key ({@code BEGIN RSA
     * PRIVATE KEY}) and a PKCS#8 one ({@code BEGIN PRIVATE KEY}) are both accepted. Vert.x accepts
     * both, and a key that worked on one kind of target and not on another would be a difference
     * nobody could see a reason for.
     *
     * @param caPem the authority to trust, or null for the runtime's own store
     * @param certPem the certificate to present, or null to present none
     * @param keyPem its private half
     * @param passphrase what opens that key, or null where it is not locked
     */
    public static SSLContext sslContext(
            String caPem, String certPem, String keyPem, String passphrase) {
        try {
            KeyManager[] keys = null;
            if (present(certPem) && present(keyPem)) {
                KeyStore mine = emptyStore();
                mine.setKeyEntry(
                        "keydra",
                        privateKeyOf(keyPem, passphrase),
                        SECRET,
                        certificatesOf(certPem).toArray(new java.security.cert.Certificate[0]));
                KeyManagerFactory factory =
                        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                factory.init(mine, SECRET);
                keys = factory.getKeyManagers();
            }

            TrustManager[] trusted = null;
            if (present(caPem)) {
                KeyStore authorities = emptyStore();
                int index = 0;
                for (X509Certificate certificate : certificatesOf(caPem)) {
                    authorities.setCertificateEntry("authority-" + index++, certificate);
                }
                TrustManagerFactory factory =
                        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
                factory.init(authorities);
                trusted = factory.getTrustManagers();
            }

            SSLContext context = SSLContext.getInstance("TLS");
            context.init(keys, trusted, null);
            return context;
        } catch (NotUsableException already) {
            throw already;
        } catch (Exception unusable) {
            throw new NotUsableException(
                    "These certificates could not be prepared for this target: "
                            + unusable.getMessage());
        }
    }

    /**
     * The passphrase on the in-memory key store.
     *
     * <p>Not a secret and not pretending to be: the store exists for the length of one method call
     * and is never written anywhere. A {@code KeyManagerFactory} requires one, so it gets one.
     */
    private static final char[] SECRET = "keydra".toCharArray();

    private static KeyStore emptyStore() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        store.load(null, SECRET);
        return store;
    }

    private static List<X509Certificate> certificatesOf(String pem) {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            JcaX509CertificateConverter converter =
                    new JcaX509CertificateConverter().setProvider(new BouncyCastleProvider());
            List<X509Certificate> found = new ArrayList<>();
            Object read;
            while ((read = parser.readObject()) != null) {
                if (read instanceof X509CertificateHolder holder) {
                    found.add(converter.getCertificate(holder));
                }
            }
            if (found.isEmpty()) {
                throw new NotUsableException("No certificate was found in that PEM.");
            }
            return found;
        } catch (NotUsableException already) {
            throw already;
        } catch (Exception unreadable) {
            throw new NotUsableException(
                    "That certificate could not be read: " + unreadable.getMessage());
        }
    }

    /**
     * The key itself, whichever of the four ways it was written.
     *
     * <p>PKCS#1 and PKCS#8, each either locked or not. BouncyCastle reads all four and returns a
     * different type for each, which is why this is a chain of instance checks rather than one
     * call: the encrypted forms hand back a thing that needs opening, and the plain forms hand back
     * the key.
     *
     * <p>Only the first key in the text is taken. A PEM holding several is somebody's mistake, and
     * silently choosing among them would make which certificate the target sees depend on paste
     * order.
     */
    private static PrivateKey privateKeyOf(String pem, String passphrase) {
        try (PEMParser parser = new PEMParser(new StringReader(pem))) {
            JcaPEMKeyConverter converter =
                    new JcaPEMKeyConverter().setProvider(new BouncyCastleProvider());
            char[] opens = present(passphrase) ? passphrase.toCharArray() : null;
            Object read;
            while ((read = parser.readObject()) != null) {
                if (read instanceof PEMEncryptedKeyPair locked) {
                    if (opens == null) {
                        throw new NotUsableException("That private key is protected.");
                    }
                    return converter
                            .getKeyPair(
                                    locked.decryptKeyPair(
                                            new JcePEMDecryptorProviderBuilder()
                                                    .setProvider(new BouncyCastleProvider())
                                                    .build(opens)))
                            .getPrivate();
                }
                if (read instanceof PKCS8EncryptedPrivateKeyInfo locked) {
                    if (opens == null) {
                        throw new NotUsableException("That private key is protected.");
                    }
                    return converter.getPrivateKey(
                            locked.decryptPrivateKeyInfo(
                                    new JceOpenSSLPKCS8DecryptorProviderBuilder()
                                            .setProvider(new BouncyCastleProvider())
                                            .build(opens)));
                }
                if (read instanceof PEMKeyPair pair) {
                    return converter.getKeyPair(pair).getPrivate();
                }
                if (read instanceof PrivateKeyInfo info) {
                    return converter.getPrivateKey(info);
                }
            }
            throw new NotUsableException("No private key was found in that PEM.");
        } catch (NotUsableException already) {
            throw already;
        } catch (Exception unreadable) {
            throw new NotUsableException(
                    "That private key could not be read: " + unreadable.getMessage());
        }
    }

    private static String[] blocksOf(String pem, String what) {
        if (!present(pem)) {
            throw new NotUsableException(what + " is empty.");
        }
        Matcher matcher = BLOCK.matcher(pem);
        java.util.List<String> found = new java.util.ArrayList<>();
        while (matcher.find() && found.size() <= MOST_BLOCKS) {
            found.add(matcher.group(2));
        }
        if (found.isEmpty()) {
            throw new NotUsableException(
                    what
                            + " does not look like PEM. It should begin with a -----BEGIN ...-----"
                            + " line and end with the matching -----END ...----- line.");
        }
        if (found.size() > MOST_BLOCKS) {
            throw new NotUsableException(what + " holds more blocks than a chain needs.");
        }
        return found.toArray(new String[0]);
    }

    private static byte[] decode(String base64, String what) {
        try {
            return Base64.getMimeDecoder().decode(base64.getBytes(StandardCharsets.US_ASCII));
        } catch (IllegalArgumentException notBase64) {
            throw new NotUsableException(
                    what
                            + " is PEM in shape but its contents are not readable. It may have been"
                            + " truncated when it was copied.");
        }
    }
}
