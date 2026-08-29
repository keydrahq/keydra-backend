package io.keydra.engine.redis;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

import io.keydra.connections.dto.ServerInfo;
import org.junit.jupiter.api.Test;

class RespServerInfoTest {

    @Test
    void detectsRedis() {
        ServerInfo info =
                RespServerInfo.parse(
                        "# Server\r\nredis_version:8.10.0\r\nredis_mode:standalone\r\n");

        assertThat(info.flavor(), equalTo(ServerInfo.FLAVOR_REDIS));
        assertThat(info.version(), equalTo("8.10.0"));
        assertThat(info.mode(), equalTo("standalone"));
    }

    @Test
    void detectsValkeyByServerNameAndPrefersItsOwnVersion() {
        // Valkey keeps redis_version for compatibility, so server_name decides the
        // flavor and valkey_version is the number worth showing.
        ServerInfo info =
                RespServerInfo.parse(
                        "# Server\r\n"
                                + "server_name:valkey\r\n"
                                + "valkey_version:9.1.1\r\n"
                                + "redis_version:7.2.4\r\n"
                                + "redis_mode:standalone\r\n");

        assertThat(info.flavor(), equalTo(ServerInfo.FLAVOR_VALKEY));
        assertThat(info.version(), equalTo("9.1.1"));
    }

    @Test
    void fallsBackToUnknownWhenNothingIsRecognisable() {
        ServerInfo info = RespServerInfo.parse("# Server\r\nsomething_else:1\r\n");

        assertThat(info.flavor(), equalTo(ServerInfo.FLAVOR_UNKNOWN));
        assertThat(info.version(), nullValue());
        assertThat(info.mode(), equalTo("unknown"));
    }

    @Test
    void toleratesNullInput() {
        assertThat(RespServerInfo.parse(null).flavor(), equalTo(ServerInfo.FLAVOR_UNKNOWN));
    }

    @Test
    void detectsKeyDbByItsOwnVersionField() {
        ServerInfo info =
                RespServerInfo.parse(
                        "# Server\r\n"
                                + "redis_version:6.3.4\r\n"
                                + "keydb_version:6.3.4\r\n"
                                + "redis_mode:standalone\r\n");

        assertThat(info.flavor(), equalTo(ServerInfo.FLAVOR_KEYDB));
    }

    /**
     * The same server, as it actually answers.
     *
     * <p>The test above passes and always did, and it was describing a KeyDB nobody ships: a real
     * 6.3.4 reports {@code redis_version:6.3.4}, no {@code keydb_version} and no {@code
     * server_name}, so every KeyDB was named as an old Redis while a green test said otherwise.
     * This block is copied from one — the multi-threading it is built around is what it announces
     * instead, and that is what identifies it.
     */
    @Test
    void detectsKeyDbFromWhatItReallyReports() {
        ServerInfo info =
                RespServerInfo.parse(
                        "# Server\r\n"
                                + "redis_version:6.3.4\r\n"
                                + "redis_mode:standalone\r\n"
                                + "gcc_version:9.4.0\r\n"
                                + "# Threads\r\n"
                                + "server_threads:2\r\n"
                                + "thread_0_clients:1\r\n"
                                + "# Keyspace\r\n"
                                + "storage_provider:none\r\n");

        assertThat(info.flavor(), equalTo(ServerInfo.FLAVOR_KEYDB));
        // Its own version, which for KeyDB is the one it reports as Redis'.
        assertThat(info.version(), equalTo("6.3.4"));
    }

    /**
     * Dragonfly, from a real server's output.
     *
     * <p>Here the assumption held: it does publish {@code dragonfly_version}, and in the section
     * the probe reads. Written down anyway, because "it held for this one" is only knowable by
     * having looked — the KeyDB entry beside it was equally confident and equally wrong.
     *
     * <p>Its own version carries the project's prefix, and that is kept as given. A version string
     * is the server's to spell.
     */
    @Test
    void detectsDragonflyFromWhatItReallyReports() {
        ServerInfo info =
                RespServerInfo.parse(
                        "# Server\r\n"
                                + "redis_version:7.4.0\r\n"
                                + "dragonfly_version:df-v1.40.1\r\n"
                                + "redis_mode:standalone\r\n"
                                + "thread_count:8\r\n");

        assertThat(info.flavor(), equalTo(ServerInfo.FLAVOR_DRAGONFLY));
        assertThat(info.version(), equalTo("df-v1.40.1"));
    }

    /**
     * And it is not mistaken for KeyDB, which the other multi-threaded fork could easily be.
     *
     * <p>Both are threaded and both say so; they use different field names for it — {@code
     * thread_count} against {@code server_threads} — which is the only reason one signal does not
     * catch the other.
     */
    @Test
    void doesNotTakeDragonflyForKeyDb() {
        ServerInfo info =
                RespServerInfo.parse(
                        "# Server\r\n"
                                + "redis_version:7.4.0\r\n"
                                + "dragonfly_version:df-v1.40.1\r\n"
                                + "thread_count:8\r\n");

        assertThat(info.flavor(), equalTo(ServerInfo.FLAVOR_DRAGONFLY));
    }

    /**
     * Garnet, from a real server's output.
     *
     * <p>The last of the four, and the only one that announces itself twice: {@code garnet_version}
     * and {@code server_name:garnet}, either of which alone would be enough. Looked at rather than
     * assumed, for the reason the KeyDB entry above records.
     *
     * <p>It is not a fork of anything — it is Microsoft's, written from scratch in C# — and the
     * {@code redis_version} it reports is a statement about the protocol it speaks rather than
     * about where it came from. Which is the whole reason flavour is read from what a server says
     * instead of inferred from a family tree.
     */
    @Test
    void detectsGarnetFromWhatItReallyReports() {
        ServerInfo info =
                RespServerInfo.parse(
                        "# Server\r\n"
                                + "garnet_version:2.1.4\r\n"
                                + "server_name:garnet\r\n"
                                + "os:Unix 6.8.0.138\r\n"
                                + "redis_version:7.4.3\r\n"
                                + "redis_mode:standalone\r\n");

        assertThat(info.flavor(), equalTo(ServerInfo.FLAVOR_GARNET));
        assertThat(info.version(), equalTo("2.1.4"));
        assertThat(info.mode(), equalTo("standalone"));
    }

    /**
     * And a build that dropped the version field would still be named, by the other half.
     *
     * <p>Not a hypothetical worth much on its own — it is here because {@code server_name} is the
     * signal that would survive a rename of the version field, and a test that only ever feeds both
     * cannot tell which one is doing the work.
     */
    @Test
    void namesGarnetFromItsNameAlone() {
        ServerInfo info =
                RespServerInfo.parse(
                        "# Server\r\n" + "server_name:garnet\r\n" + "redis_version:7.4.3\r\n");

        assertThat(info.flavor(), equalTo(ServerInfo.FLAVOR_GARNET));
        // Nothing of its own to report, so the compatibility version stands in.
        assertThat(info.version(), equalTo("7.4.3"));
    }

    /** And a Redis is still a Redis: nothing here reports threads, so nothing is mistaken. */
    @Test
    void doesNotTakeAPlainRedisForKeyDb() {
        ServerInfo info =
                RespServerInfo.parse(
                        "# Server\r\n" + "redis_version:8.10.1\r\n" + "redis_mode:standalone\r\n");

        assertThat(info.flavor(), equalTo(ServerInfo.FLAVOR_REDIS));
    }

    @Test
    void detectsDragonflyAndReportsItsOwnVersion() {
        // Dragonfly claims a Redis version it is compatible with, not the one it is.
        ServerInfo info =
                RespServerInfo.parse(
                        "# Server\r\n"
                                + "redis_version:6.2.11\r\n"
                                + "dragonfly_version:df-v1.35.0\r\n"
                                + "redis_mode:standalone\r\n");

        assertThat(info.flavor(), equalTo(ServerInfo.FLAVOR_DRAGONFLY));
        assertThat(info.version(), equalTo("df-v1.35.0"));
    }

    @Test
    void detectsGarnet() {
        ServerInfo info =
                RespServerInfo.parse(
                        "# Server\r\nredis_version:7.2.5\r\ngarnet_version:1.0.61\r\n");

        assertThat(info.flavor(), equalTo(ServerInfo.FLAVOR_GARNET));
        assertThat(info.version(), equalTo("1.0.61"));
    }

    @Test
    void doesNotMistakeAForkForAnOldRedis() {
        // The compatibility field is present on every fork, so reading it first would
        // name them all "redis" — and a UI would then hide what they can actually do.
        ServerInfo info =
                RespServerInfo.parse(
                        "# Server\r\nredis_version:6.2.11\r\ndragonfly_version:df-v1.35.0\r\n");

        assertThat(info.flavor(), org.hamcrest.Matchers.not(equalTo(ServerInfo.FLAVOR_REDIS)));
    }

    @Test
    void keepsTheModeEvenWhenTheFlavorIsUnrecognisable() {
        // Topology and product are separate facts; not knowing one does not lose the other.
        ServerInfo info = RespServerInfo.parse("# Server\r\nredis_mode:cluster\r\n");

        assertThat(info.flavor(), equalTo(ServerInfo.FLAVOR_UNKNOWN));
        assertThat(info.mode(), equalTo("cluster"));
    }
}
