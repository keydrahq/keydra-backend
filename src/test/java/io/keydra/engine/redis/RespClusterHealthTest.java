package io.keydra.engine.redis;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import io.keydra.engine.ClusterHealth;
import org.junit.jupiter.api.Test;

/**
 * What {@code CLUSTER INFO} says, and what to do when it says less than expected.
 *
 * <p>Worth a test of its own because this is the half of the topology the node list cannot give,
 * and because the page built on it draws a red banner from one field. A parser that answered zero
 * for a field a fork spells differently would draw that banner on a healthy cluster.
 */
class RespClusterHealthTest {

    private static final String HEALTHY =
            """
            cluster_state:ok
            cluster_slots_assigned:16384
            cluster_slots_ok:16384
            cluster_slots_pfail:0
            cluster_slots_fail:0
            cluster_known_nodes:6
            cluster_size:3
            cluster_current_epoch:6
            cluster_my_epoch:2
            cluster_stats_messages_sent:1483972
            """;

    @Test
    void readsEveryFieldThePageDrawsFrom() {
        ClusterHealth health = RespClusterHealth.parse(HEALTHY);

        assertThat(health.state(), is("ok"));
        assertThat(health.isServing(), is(true));
        assertThat(health.slotsAssigned(), is(16384));
        assertThat(health.slotsOk(), is(16384));
        assertThat(health.knownNodes(), is(6));
        assertThat(health.size(), is(3));
        assertThat(health.currentEpoch(), is(6L));
    }

    @Test
    void aClusterThatIsNotServingSaysSo() {
        ClusterHealth health =
                RespClusterHealth.parse("cluster_state:fail\ncluster_slots_assigned:16384\n");

        assertThat(health.isServing(), is(false));
        // Every slot assigned and the cluster still refusing is exactly the case a coverage bar
        // cannot show, and the reason this is read at all.
        assertThat(health.slotsAssigned(), is(16384));
    }

    /** A standalone server refuses the command, and that is an answer about what it is. */
    @Test
    void nothingToReadIsNoHealthRatherThanAnEmptyOne() {
        assertThat(RespClusterHealth.parse(null), is(nullValue()));
        assertThat(RespClusterHealth.parse("  "), is(nullValue()));
        assertThat(RespClusterHealth.parse("some_other_field:1"), is(nullValue()));
    }

    /** A fork that answers with fewer fields gets a partial picture, not an exception. */
    @Test
    void aFieldThatIsMissingOrNotANumberReadsAsZero() {
        ClusterHealth health =
                RespClusterHealth.parse("cluster_state:ok\ncluster_slots_assigned:lots\n");

        assertThat(health.isServing(), is(true));
        assertThat(health.slotsAssigned(), is(0));
        assertThat(health.knownNodes(), is(0));
    }
}
