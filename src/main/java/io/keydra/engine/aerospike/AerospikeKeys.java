package io.keydra.engine.aerospike;

import com.aerospike.client.Key;
import com.aerospike.client.Record;
import io.keydra.connections.entity.ConnectionProfile;

/**
 * Turning an Aerospike record into something with a name, and back.
 *
 * <p>This is where the awkwardness of the two models lives, so it is worth stating plainly. Keydra
 * addresses a key by one string. Aerospike addresses a record by three things — a namespace, a set
 * and a user key — and the namespace is fixed per profile, which leaves two. Those two become one
 * string with a colon between them, which is the delimiter the namespace tree already splits on: a
 * target's sets become the tree's first level, and a glob like {@code sessions:*} is a scan of one
 * set rather than of everything.
 *
 * <p><strong>And the part that cannot be worked around.</strong> Aerospike stores the user key only
 * when the application that wrote the record asked it to, and the default is not to — what the
 * server keeps is a 20-byte digest. So a record written by an ordinary application has no name to
 * show, and nothing here can invent one. Those are named by their digest, marked so nobody reads it
 * as a key somebody chose: {@code set:@a1b2c3…}. They can be read and deleted by that name, because
 * the digest is what the server addresses them by anyway; they cannot be searched for by a name
 * they were never given.
 */
final class AerospikeKeys {

    /** What marks a name as a digest rather than something a person chose. */
    static final String DIGEST_MARK = "@";

    private static final String SEPARATOR = ":";
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private AerospikeKeys() {}

    /** The name Keydra shows for a record. */
    static String nameOf(Key key) {
        String set = key.setName == null ? "" : key.setName;
        if (key.userKey != null) {
            return set + SEPARATOR + key.userKey;
        }
        return set + SEPARATOR + DIGEST_MARK + hex(key.digest);
    }

    /**
     * The record a name refers to.
     *
     * <p>A name with no separator is a record in the null set, which Aerospike allows and which
     * would otherwise be unaddressable.
     */
    static Key keyOf(ConnectionProfile profile, String name) {
        int cut = name.indexOf(SEPARATOR);
        String set = cut < 0 ? null : name.substring(0, cut);
        String rest = cut < 0 ? name : name.substring(cut + 1);
        if (rest.startsWith(DIGEST_MARK)) {
            return new Key(
                    profile.namespace, unhex(rest.substring(DIGEST_MARK.length())), set, null);
        }
        return new Key(profile.namespace, set, rest);
    }

    /** The set a glob selects, or null where it selects more than one. */
    static String setSelectedBy(String match) {
        if (match == null || match.isBlank()) {
            return null;
        }
        int cut = match.indexOf(SEPARATOR);
        if (cut <= 0) {
            return null;
        }
        String set = match.substring(0, cut);
        // A pattern in the set part selects several sets, and the scan has to look at all of them.
        return set.chars().anyMatch(c -> c == '*' || c == '?' || c == '[') ? null : set;
    }

    /** How long a record has left, in the seconds Keydra counts. */
    static long ttlOf(Record record) {
        // Aerospike's own convention: 0 is a record that never expires, and the browser spells
        // that -1 because that is what it spells it for every other store.
        return record.expiration == 0 ? -1 : record.getTimeToLive();
    }

    private static String hex(byte[] digest) {
        char[] out = new char[digest.length * 2];
        for (int i = 0; i < digest.length; i++) {
            out[i * 2] = HEX[(digest[i] >> 4) & 0xF];
            out[i * 2 + 1] = HEX[digest[i] & 0xF];
        }
        return new String(out);
    }

    private static byte[] unhex(String text) {
        byte[] out = new byte[text.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(text.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
