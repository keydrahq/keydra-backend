package io.keydra.engine.redis;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * What Keydra has to do differently because a store is not a fork of Redis.
 *
 * <p>Every sample here is trimmed from a running Garnet 2.1.4 rather than composed, because the
 * whole point of these cases is what a real one does that a fork does not. Garnet speaks RESP and
 * reports a {@code redis_version}, and both of those say something narrower than they look: they
 * are claims about a protocol, not about a lineage. Everything below is a place where believing the
 * wider reading would have produced an empty column or a failed migration.
 */
class RespGarnetReadingsTest {

    /**
     * Garnet files nothing under {@code # Keyspace}. Its store's figures live under {@code #
     * Store_DB_0} and are about index buckets and log addresses — none of them is a key count.
     */
    private static final String GARNET_INFO =
            """
            # Server
            garnet_version:2.1.4
            server_name:garnet
            redis_version:7.4.3
            redis_mode:standalone

            # Memory
            proc_physical_memory_size:89206784
            proc_peak_physical_memory_size:89206784
            total_main_store_size:167773184

            # Stats
            total_found:41
            total_notfound:3

            # Store_DB_0
            IndexBucketCount:2097152
            Log.TailAddress:112
            """;

    private static final String REDIS_INFO =
            """
            # Memory
            used_memory:1048576
            used_memory_peak:2097152

            # Stats
            keyspace_hits:900
            keyspace_misses:100

            # Keyspace
            db0:keys=12,expires=3,avg_ttl=0
            """;

    /** A store that spells the reading Redis' way is read Redis' way, and nothing else happens. */
    @Test
    void prefersTheNameTheReadingUsuallyHas() {
        Map<String, Map<String, String>> sections = RespInfo.parse(REDIS_INFO);

        assertThat(RespServerMetrics.reading(sections, "used_memory"), equalTo(1_048_576L));
        assertThat(RespServerMetrics.reading(sections, "keyspace_hits"), equalTo(900L));
    }

    /**
     * And a store that does not gets read by the other name.
     *
     * <p>Resident size is not what {@code used_memory} measures, and the substitution is deliberate
     * rather than accidental: they answer the same question a fleet view is asking — how much of
     * this machine is this server using — and the alternative was an empty column on every Garnet.
     */
    @Test
    void fallsBackToTheNameThisStoreUses() {
        Map<String, Map<String, String>> sections = RespInfo.parse(GARNET_INFO);

        assertThat(RespServerMetrics.reading(sections, "used_memory"), equalTo(89_206_784L));
        assertThat(RespServerMetrics.reading(sections, "used_memory_peak"), equalTo(89_206_784L));
        assertThat(RespServerMetrics.reading(sections, "keyspace_hits"), equalTo(41L));
        assertThat(RespServerMetrics.reading(sections, "keyspace_misses"), equalTo(3L));
    }

    /**
     * The tempting wrong answer, written down so nobody reaches for it later.
     *
     * <p>{@code total_main_store_size} reads 160 MiB on an empty Garnet, because it is how much the
     * store has reserved rather than how much it is using. A memory column drawn from it would show
     * every Garnet as busy from the moment it started.
     */
    @Test
    void doesNotReadCapacityAsUse() {
        Map<String, Map<String, String>> sections = RespInfo.parse(GARNET_INFO);

        assertThat(RespInfo.number(sections, "total_main_store_size"), equalTo(167_773_184L));
        assertThat(RespServerMetrics.reading(sections, "used_memory"), equalTo(89_206_784L));
    }

    /**
     * No keyspace section at all is a different answer from a database that holds nothing.
     *
     * <p>Null is what tells {@code RespServerMetrics} to go and ask DBSIZE; zero is a store saying
     * it already knows. Collapsing the two would either cost every Redis an extra round trip per
     * sample or leave every Garnet reporting no keys.
     */
    @Test
    void tellsAMissingKeyspaceApartFromAnEmptyOne() {
        assertThat(RespInfo.keyCount(RespInfo.parse(GARNET_INFO), 0), is(nullValue()));
        assertThat(RespInfo.keyCount(RespInfo.parse(REDIS_INFO), 0), equalTo(12L));
        // A database the store did not mention, in a store that reported a keyspace: it is empty.
        assertThat(RespInfo.keyCount(RespInfo.parse(REDIS_INFO), 9), equalTo(0L));
    }

    /**
     * Every way a store can say it will not take this dump.
     *
     * <p>The first is Redis' and the forks'. The second and third are Garnet's, and the third is
     * the one that mattered: its RESTORE takes no REPLACE, so a migration told to overwrite was
     * refused on every single key rather than on some, and without this the fallback that exists
     * for exactly this situation never ran.
     */
    @Test
    void treatsEveryRefusalOfADumpAsAReasonToCopyValuesInstead() {
        RespKeyTransfer transfer = new RespKeyTransfer(null);

        assertThat(
                transfer.isIncompatibleFormat("ERR DUMP payload version or checksum are wrong"),
                is(true));
        assertThat(
                transfer.isIncompatibleFormat("ERR RESTORE currently only supports string types"),
                is(true));
        assertThat(
                transfer.isIncompatibleFormat(
                        "ERR wrong number of arguments for 'RESTORE' command"),
                is(true));
    }

    /** And something that is not one of them is still a refusal of that key, not of the format. */
    @Test
    void doesNotReadEveryFailureAsTheWrongFormat() {
        RespKeyTransfer transfer = new RespKeyTransfer(null);

        assertThat(
                transfer.isIncompatibleFormat("BUSYKEY Target key name already exists."),
                is(false));
        assertThat(
                transfer.isIncompatibleFormat(
                        "OOM command not allowed when used memory > 'maxmemory'"),
                is(false));
        assertThat(transfer.isIncompatibleFormat(null), is(false));
    }
}
