package io.keydra.engine.redis;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import io.keydra.engine.ClusterNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class RespClusterNodesTest {

    /** A real three-node reply: two primaries with slots, one replica. */
    private static final String THREE_NODES =
            "07c37dfeb235213a872192d90877d0cd55635b91 127.0.0.1:30003@31003 master - 0"
                    + " 1426238317239 3 connected 10923-16383\n"
                    + "67ed2db8d677e59ec4a4cefb06858cf2a1a89fa1 127.0.0.1:30001@31001 myself,master"
                    + " - 0 0 1 connected 0-5460\n"
                    + "292f8b365bb7edb5e285caf0b7e6ddc7265d2f4f 127.0.0.1:30004@31004 slave"
                    + " 67ed2db8d677e59ec4a4cefb06858cf2a1a89fa1 0 1426238317741 5 connected\n";

    @Test
    void readsEveryNode() {
        assertThat(RespClusterNodes.parse(THREE_NODES), hasSize(3));
    }

    @Test
    void tellsPrimariesFromReplicas() {
        List<ClusterNode> nodes = RespClusterNodes.parse(THREE_NODES);

        assertThat(nodes.get(0).role(), equalTo(ClusterNode.ROLE_PRIMARY));
        assertThat(nodes.get(2).role(), equalTo(ClusterNode.ROLE_REPLICA));
    }

    @Test
    void namesThePrimaryAReplicaFollows() {
        List<ClusterNode> nodes = RespClusterNodes.parse(THREE_NODES);

        assertThat(nodes.get(2).primaryId(), equalTo("67ed2db8d677e59ec4a4cefb06858cf2a1a89fa1"));
        // A primary reports "-", which is not an id.
        assertThat(nodes.get(0).primaryId(), nullValue());
    }

    @Test
    void marksTheNodeThatAnswered() {
        List<ClusterNode> nodes = RespClusterNodes.parse(THREE_NODES);

        assertThat(nodes.get(1).isSelf(), is(true));
        assertThat(nodes.get(0).isSelf(), is(false));
    }

    @Test
    void dropsTheBusPortFromTheAddress() {
        // The port after @ is for nodes talking to each other; nothing else can use it.
        assertThat(
                RespClusterNodes.parse(THREE_NODES).get(0).address(), equalTo("127.0.0.1:30003"));
    }

    @Test
    void readsTheSlotRangesAPrimaryServes() {
        List<ClusterNode> nodes = RespClusterNodes.parse(THREE_NODES);

        assertThat(nodes.get(0).slots(), contains(new ClusterNode.SlotRange(10923, 16383)));
        assertThat(nodes.get(1).slots(), contains(new ClusterNode.SlotRange(0, 5460)));
        // A replica serves none of its own.
        assertThat(nodes.get(2).slots(), empty());
    }

    @Test
    void countsTheSlotsInARange() {
        assertThat(new ClusterNode.SlotRange(0, 5460).count(), equalTo(5461));
    }

    @Test
    void readsASingleSlotAsItsOwnRange() {
        String single = "abc 127.0.0.1:7000@17000 myself,master - 0 0 1 connected 42\n";

        assertThat(
                RespClusterNodes.parse(single).get(0).slots(),
                contains(new ClusterNode.SlotRange(42, 42)));
    }

    @Test
    void ignoresSlotsThatAreMidMigration() {
        // A bracketed entry belongs to the node only while the migration lasts, so
        // reporting it as served would overstate what the node answers for.
        String migrating =
                "abc 127.0.0.1:7000@17000 myself,master - 0 0 1 connected 0-100"
                        + " [101-<-def456]\n";

        assertThat(
                RespClusterNodes.parse(migrating).get(0).slots(),
                contains(new ClusterNode.SlotRange(0, 100)));
    }

    /**
     * The other half of a bracketed entry: what it says is happening.
     *
     * <p>Skipping them for the ranges is right and throwing them away is not. While a reshard runs
     * this is the only part of a cluster's description that is moving, and it is what a topology
     * has to show for the picture to be of today rather than of yesterday.
     */
    @Test
    void readsTheSlotsThatAreOnTheMove() {
        String resharding =
                "abc 127.0.0.1:7000@17000 myself,master - 0 0 1 connected 0-100"
                        + " [101-<-def456] [202->-ghi789]\n";

        List<ClusterNode.SlotMigration> moving =
                RespClusterNodes.parse(resharding).get(0).migrations();

        assertThat(moving, hasSize(2));
        assertThat(moving.get(0).slot(), equalTo(101));
        assertThat(moving.get(0).direction(), equalTo(ClusterNode.SlotMigration.Direction.IN));
        assertThat(moving.get(0).peerId(), equalTo("def456"));
        assertThat(moving.get(1).slot(), equalTo(202));
        assertThat(moving.get(1).direction(), equalTo(ClusterNode.SlotMigration.Direction.OUT));
        assertThat(moving.get(1).peerId(), equalTo("ghi789"));
    }

    /** A cluster nobody is resharding says nothing, rather than saying nothing is moving. */
    @Test
    void aClusterAtRestReportsNoMigrations() {
        assertThat(RespClusterNodes.parse(THREE_NODES).get(0).migrations(), hasSize(0));
    }

    @Test
    void keepsTheFlagsItDoesNotInterpret() {
        assertThat(RespClusterNodes.parse(THREE_NODES).get(1).flags(), hasItem("myself"));
    }

    @Test
    void reportsTheLinkState() {
        assertThat(RespClusterNodes.parse(THREE_NODES).get(0).linkState(), equalTo("connected"));
    }

    @Test
    void answersEmptyForAServerWithNoCluster() {
        assertThat(RespClusterNodes.parse(null), empty());
        assertThat(RespClusterNodes.parse(""), empty());
    }

    @Test
    void skipsALineThatIsTooShortToBeANode() {
        assertThat(RespClusterNodes.parse("garbage line\n"), empty());
    }
}
