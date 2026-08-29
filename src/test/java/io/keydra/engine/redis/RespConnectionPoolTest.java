package io.keydra.engine.redis;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.entity.ConnectionType;
import io.keydra.tunnels.TunnelEndpoint;
import org.junit.jupiter.api.Test;

class RespConnectionPoolTest {

    private static ConnectionProfile profile() {
        ConnectionProfile profile = new ConnectionProfile();
        profile.host = "localhost";
        profile.port = 6379;
        profile.type = ConnectionType.STANDALONE;
        return profile;
    }

    /** The URI for a profile reached directly, which is what every case here uses but one. */
    private static String uriFor(ConnectionProfile profile) {
        return RespConnectionPool.connectionString(
                profile, TunnelEndpoint.direct(profile.host, profile.port));
    }

    @Test
    void buildsAPlainUri() {
        assertThat(uriFor(profile()), equalTo("redis://localhost:6379"));
    }

    @Test
    void usesRedissForTls() {
        ConnectionProfile profile = profile();
        profile.tls = true;

        assertThat(
                RespConnectionPool.connectionString(
                        profile, TunnelEndpoint.direct(profile.host, profile.port)),
                equalTo("rediss://localhost:6379"));
    }

    @Test
    void appendsTheDatabaseIndexOnlyWhenNonZero() {
        ConnectionProfile profile = profile();
        profile.database = 3;

        assertThat(
                RespConnectionPool.connectionString(
                        profile, TunnelEndpoint.direct(profile.host, profile.port)),
                equalTo("redis://localhost:6379/3"));
    }

    @Test
    void urlEncodesCredentialsSoPunctuationCannotCorruptTheUri() {
        ConnectionProfile profile = profile();
        profile.username = "adm:in";
        profile.password = "p@ss/word";

        assertThat(
                RespConnectionPool.connectionString(
                        profile, TunnelEndpoint.direct(profile.host, profile.port)),
                equalTo("redis://adm%3Ain:p%40ss%2Fword@localhost:6379"));
    }

    @Test
    void omitsTheDatabaseForClusterTargets() {
        ConnectionProfile profile = profile();
        profile.type = ConnectionType.CLUSTER;
        profile.database = 5;

        assertThat(
                RespConnectionPool.connectionString(
                        profile, TunnelEndpoint.direct(profile.host, profile.port)),
                equalTo("redis://localhost:6379"));
    }

    @Test
    void dialsTheLocalEndOfATunnelRatherThanTheTarget() {
        ConnectionProfile profile = new ConnectionProfile();
        profile.host = "redis.internal";
        profile.port = 6379;

        // Through a tunnel the client must connect locally; connecting to the target's own
        // address is exactly what the tunnel exists because it cannot do.
        String uri =
                RespConnectionPool.connectionString(
                        profile, new TunnelEndpoint("127.0.0.1", 41234, true));

        assertThat(uri, equalTo("redis://127.0.0.1:41234"));
    }
}
