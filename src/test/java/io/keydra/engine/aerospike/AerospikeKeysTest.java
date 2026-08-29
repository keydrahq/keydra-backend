package io.keydra.engine.aerospike;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

import com.aerospike.client.Key;
import io.keydra.connections.entity.ConnectionProfile;
import org.junit.jupiter.api.Test;

/**
 * The join between two models of what a key is, which is where an Aerospike target is either usable
 * or it is not.
 *
 * <p>Keydra addresses a key by one string; Aerospike addresses a record by a namespace, a set and a
 * user key. The namespace is the profile's, which leaves two to fit into one — and the fit has to
 * survive the round trip, because a name that cannot be turned back into the record it came from is
 * a name nothing can be done with.
 */
class AerospikeKeysTest {

    private static ConnectionProfile pointingAt(String namespace) {
        ConnectionProfile profile = new ConnectionProfile();
        profile.namespace = namespace;
        return profile;
    }

    @Test
    void namesARecordBySetAndKey() {
        Key key = new Key("test", "sessions", "abc123");

        assertThat(AerospikeKeys.nameOf(key), equalTo("sessions:abc123"));
    }

    /** And back again, to the same record — which is the only thing that makes the name useful. */
    @Test
    void findsTheRecordAgainFromItsName() {
        ConnectionProfile profile = pointingAt("test");
        Key original = new Key("test", "sessions", "abc123");

        Key round = AerospikeKeys.keyOf(profile, AerospikeKeys.nameOf(original));

        assertThat(round.namespace, equalTo("test"));
        assertThat(round.setName, equalTo("sessions"));
        assertThat(round.userKey.toString(), equalTo("abc123"));
    }

    /**
     * A record whose name was never stored is named by its digest, and marked.
     *
     * <p>This is the ordinary case rather than the odd one: Aerospike keeps the user key only when
     * the application that wrote the record asked it to, and the default is not to. The mark is
     * what stops somebody reading twenty bytes of hex as a name a person chose.
     */
    @Test
    void namesARecordWithNoStoredKeyByItsDigest() {
        Key unnamed = new Key("test", "sessions", "abc123");
        Key asStored = new Key("test", unnamed.digest, "sessions", null);

        String name = AerospikeKeys.nameOf(asStored);

        assertThat(name, startsWith("sessions:@"));
        assertThat(name.length(), equalTo("sessions:@".length() + 40));
    }

    /** And that name still addresses it, because the digest is what the server goes by anyway. */
    @Test
    void findsARecordAgainFromItsDigestName() {
        ConnectionProfile profile = pointingAt("test");
        Key original = new Key("test", "sessions", "abc123");
        Key asStored = new Key("test", original.digest, "sessions", null);

        Key round = AerospikeKeys.keyOf(profile, AerospikeKeys.nameOf(asStored));

        assertThat(round.digest, equalTo(original.digest));
        assertThat(round.setName, equalTo("sessions"));
    }

    /** Aerospike allows a record in no set at all, and it has to be addressable too. */
    @Test
    void handlesARecordInNoSet() {
        ConnectionProfile profile = pointingAt("test");

        Key round = AerospikeKeys.keyOf(profile, "loose");

        assertThat(round.setName, is(nullValue()));
        assertThat(round.userKey.toString(), equalTo("loose"));
    }

    /**
     * A glob whose first segment is a literal names one set, and the scan can go straight to it.
     *
     * <p>Which is the payoff for spelling names this way: Aerospike scans a set, not a keyspace, so
     * a pattern that says which set it wants is the difference between reading one set and reading
     * the namespace.
     */
    @Test
    void readsTheSetOutOfAGlob() {
        assertThat(AerospikeKeys.setSelectedBy("sessions:*"), equalTo("sessions"));
        assertThat(AerospikeKeys.setSelectedBy("sessions:abc*"), equalTo("sessions"));
    }

    /** And a glob that does not is a scan of everything, said as null rather than guessed at. */
    @Test
    void saysNothingWhenAGlobSpansSets() {
        assertThat(AerospikeKeys.setSelectedBy("*"), is(nullValue()));
        assertThat(AerospikeKeys.setSelectedBy("sess*:abc"), is(nullValue()));
        assertThat(AerospikeKeys.setSelectedBy("noseparator"), is(nullValue()));
        assertThat(AerospikeKeys.setSelectedBy(null), is(nullValue()));
    }
}
