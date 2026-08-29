package io.keydra.resources;

import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;

/**
 * An SSH server inside the test JVM.
 *
 * <p>A container was tried first and abandoned: rootless Podman on this machine gives containers no
 * routable address, so a container cannot reach another container and the arrangement a tunnel test
 * needs cannot be built there at all.
 *
 * <p>Running the server in-process is better anyway. It starts in milliseconds, and it can be
 * <em>asked</em> what it was told to forward — which is direct evidence that the traffic went
 * through the tunnel rather than around it, and stronger than inferring it from an unreachable
 * address.
 */
public class InProcessSshResource implements QuarkusTestResourceLifecycleManager {

    public static final String SSH_HOST = "keydra.test.ssh.host";
    public static final String SSH_PORT = "keydra.test.ssh.port";
    public static final String SSH_USER = "keydra.test.ssh.user";
    public static final String SSH_PASSWORD = "keydra.test.ssh.password";

    private static final String USERNAME = "tunneller";
    private static final String PASSWORD = "tunnel-secret";

    /** How many forwards this server has been asked to open, for tests to assert on. */
    private static final AtomicInteger FORWARDS = new AtomicInteger();

    /** The last destination it was asked to reach, likewise. */
    private static volatile SshdSocketAddress lastDestination;

    /**
     * How many sessions have been authenticated.
     *
     * <p>The figure that says whether one jump host is one connection: twenty targets behind one
     * jump host should be one session with twenty forwards, and a test that counted only forwards
     * could not tell that from twenty sessions.
     */
    private static final AtomicInteger SESSIONS = new AtomicInteger();

    private SshServer server;
    private Path hostKey;

    public static int forwardCount() {
        return FORWARDS.get();
    }

    public static SshdSocketAddress lastDestination() {
        return lastDestination;
    }

    public static int sessionCount() {
        return SESSIONS.get();
    }

    public static void resetForwards() {
        FORWARDS.set(0);
        SESSIONS.set(0);
        lastDestination = null;
    }

    @Override
    public Map<String, String> start() {
        try {
            hostKey = Files.createTempFile("keydra-test-hostkey", ".ser");
            server = SshServer.setUpDefaultServer();
            server.setPort(0);
            server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
            server.setPasswordAuthenticator(
                    (username, password, session) -> {
                        boolean allowed = USERNAME.equals(username) && PASSWORD.equals(password);
                        if (allowed) {
                            SESSIONS.incrementAndGet();
                        }
                        return allowed;
                    });
            // The filter is asked before every direct-tcpip channel, which is what a local
            // port forward opens — so counting here counts the traffic the tunnel carried.
            // The event listener was tried first and does not fire for local forwards: those
            // are channels, not the explicit tcpip-forward request it reports.
            server.setForwardingFilter(
                    new AcceptAllForwardingFilter() {
                        @Override
                        public boolean canConnect(
                                org.apache.sshd.server.forward.ForwardingFilter.Type type,
                                SshdSocketAddress address,
                                org.apache.sshd.common.session.Session session) {
                            FORWARDS.incrementAndGet();
                            lastDestination = address;
                            return super.canConnect(type, address, session);
                        }
                    });
            server.start();

            return Map.of(
                    SSH_HOST,
                    "127.0.0.1",
                    SSH_PORT,
                    String.valueOf(server.getPort()),
                    SSH_USER,
                    USERNAME,
                    SSH_PASSWORD,
                    PASSWORD);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the test SSH server", e);
        }
    }

    @Override
    public void stop() {
        try {
            if (server != null) {
                server.stop(true);
            }
            if (hostKey != null) {
                Files.deleteIfExists(hostKey);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Could not stop the test SSH server", e);
        }
    }
}
