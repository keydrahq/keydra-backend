package io.keydra.tunnels.service;

import io.keydra.common.workload.Workload;
import io.keydra.tunnels.TunnelEndpoint;
import io.keydra.tunnels.entity.SshTunnel;
import io.keydra.tunnels.exception.TunnelException;
import io.quarkus.runtime.ShutdownEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.PublicKey;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.AttributeRepository;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Opens and keeps the SSH tunnels that things are reached through.
 *
 * <p>One session per jump host, not per target. That is the whole point of the tunnel being a row:
 * twenty targets behind one jump host used to be twenty SSH connections and twenty copies of one
 * key, and are now one connection with twenty local forwards hanging off it.
 *
 * <p>A forward is a local port that leads to one address on the far side. Everything above this
 * connects to a local address and never learns it is not the real one.
 */
@ApplicationScoped
public class TunnelManager implements Workload {

    private static final Logger LOG = Logger.getLogger(TunnelManager.class);

    /** One open session, and the forwards opened through it, keyed by where they lead. */
    private record Open(ClientSession session, Map<String, TunnelEndpoint> forwards) {}

    private final Vertx vertx;
    private final SshClient client;
    private final Duration connectTimeout;
    private final Map<Long, Open> sessions = new ConcurrentHashMap<>();

    /**
     * One lock per jump host, so two things opening the same tunnel at once open one session.
     *
     * <p>Without it the two race, both build a session, and the second replaces the first in the
     * map — which leaks an authenticated connection and quietly undoes the whole reason the tunnel
     * became a row. A lock rather than {@code computeIfAbsent} because the work inside it is a
     * network round trip, and holding a map's bin lock across one is how a map stops being usable.
     */
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    @Inject
    TunnelManager(
            Vertx vertx,
            @ConfigProperty(name = "keydra.tunnel.connect-timeout", defaultValue = "10s")
                    Duration connectTimeout) {
        this.vertx = vertx;
        this.connectTimeout = connectTimeout;
        this.client = SshClient.setUpDefaultClient();
        // Host keys are checked against what the row pins, or accepted when it pins nothing.
        // The client's own known_hosts file is not consulted: Keydra may have no filesystem
        // worth keeping one on, and a file nobody can see is not a decision anybody made.
        this.client.setServerKeyVerifier((session, address, key) -> verify(session, key));
        this.client.start();
    }

    void onStop(@Observes ShutdownEvent event) {
        Set.copyOf(sessions.keySet()).forEach(this::close);
        client.stop();
    }

    /**
     * A local address that leads to {@code host:port} on the far side of the tunnel.
     *
     * <p>Blocking work — a socket, a key exchange, an authentication — so it runs through {@code
     * executeBlocking} rather than {@code runSubscriptionOn}: the latter would leave everything
     * downstream on a worker thread, and downstream of this is Hibernate Reactive, which runs only
     * on its own context.
     */
    public Uni<TunnelEndpoint> forwardTo(SshTunnel tunnel, String host, int port) {
        TunnelEndpoint existing = cached(tunnel.id, host, port);
        if (existing != null) {
            return Uni.createFrom().item(existing);
        }
        return Uni.createFrom()
                .completionStage(
                        () ->
                                vertx.executeBlocking(() -> open(tunnel, host, port), false)
                                        .toCompletionStage());
    }

    /** Whether the jump host answers and lets us in, and what key it presented. */
    public Uni<String> check(SshTunnel tunnel) {
        return Uni.createFrom()
                .completionStage(
                        () ->
                                vertx.executeBlocking(() -> probe(tunnel), false)
                                        .toCompletionStage());
    }

    /** Drops a jump host's session and every forward through it. */
    public void close(Long tunnelId) {
        if (tunnelId == null) {
            return;
        }
        Open open = sessions.remove(tunnelId);
        if (open == null) {
            return;
        }
        try {
            open.session().close(false);
        } catch (Exception stubborn) {
            LOG.debugf(stubborn, "Tunnel %d did not close cleanly", tunnelId);
        }
    }

    // --- The blocking half, on a worker thread -----------------------------

    private TunnelEndpoint cached(Long tunnelId, String host, int port) {
        Open open = tunnelId == null ? null : sessions.get(tunnelId);
        if (open == null || !open.session().isOpen()) {
            return null;
        }
        return open.forwards().get(host + ":" + port);
    }

    private TunnelEndpoint open(SshTunnel tunnel, String host, int port) {
        synchronized (locks.computeIfAbsent(tunnel.id, ignored -> new Object())) {
            return openLocked(tunnel, host, port);
        }
    }

    private TunnelEndpoint openLocked(SshTunnel tunnel, String host, int port) {
        Open open = sessions.get(tunnel.id);
        if (open == null || !open.session().isOpen()) {
            close(tunnel.id);
            open = new Open(connect(tunnel), new ConcurrentHashMap<>());
            sessions.put(tunnel.id, open);
        }
        String where = host + ":" + port;
        TunnelEndpoint already = open.forwards().get(where);
        if (already != null) {
            return already;
        }
        try {
            // Port 0 asks the operating system for a free one, so two forwards never collide
            // and nothing has to be configured.
            SshdSocketAddress local =
                    open.session()
                            .startLocalPortForwarding(
                                    new SshdSocketAddress("127.0.0.1", 0),
                                    new SshdSocketAddress(host, port));
            TunnelEndpoint endpoint =
                    new TunnelEndpoint(local.getHostName(), local.getPort(), true);
            open.forwards().put(where, endpoint);
            LOG.infof("Tunnel to %s open via %s", where, tunnel.host);
            return endpoint;
        } catch (IOException unreachable) {
            throw new TunnelException(
                    "Could not reach " + where + " via " + tunnel.host, unreachable);
        }
    }

    private ClientSession connect(SshTunnel tunnel) {
        try {
            // What to expect travels in the connection context rather than on the session,
            // because the session does not exist yet when the key has to be judged: the
            // verifier runs during the handshake, and anything set afterwards is too late.
            // An empty string rather than no context at all: the interface has no empty
            // instance, and the verifier already treats blank as "pin nothing".
            AttributeRepository context =
                    AttributeRepository.ofKeyValuePair(
                            EXPECTED,
                            tunnel.hostKeyFingerprint == null
                                    ? ""
                                    : tunnel.hostKeyFingerprint.trim());
            ClientSession session =
                    client.connect(tunnel.username, tunnel.host, tunnel.port, context)
                            .verify(connectTimeout.toMillis())
                            .getSession();
            authenticate(session, tunnel);
            session.auth().verify(connectTimeout.toMillis());
            return session;
        } catch (TunnelException already) {
            throw already;
        } catch (Exception unreachable) {
            LOG.debugf(unreachable, "Tunnel to %s failed", tunnel.host);
            // A refused host key is thrown from inside the handshake and comes back wrapped.
            // The wrapper says "could not connect"; the cause says why, and why is the whole
            // point of pinning a key.
            TunnelException refusal = refusalWithin(unreachable);
            throw refusal != null
                    ? refusal
                    : new TunnelException(
                            "Could not open a tunnel via " + tunnel.host, unreachable);
        }
    }

    /** The tunnel's own complaint, if one is somewhere down the cause chain. */
    private static TunnelException refusalWithin(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof TunnelException refusal) {
                return refusal;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return null;
    }

    /** Connects, authenticates, and answers the fingerprint the jump host presented. */
    private String probe(SshTunnel tunnel) {
        try (ClientSession session = connect(tunnel)) {
            String seen = session.getAttribute(PRESENTED);
            return seen == null ? "" : seen;
        } catch (TunnelException already) {
            throw already;
        } catch (Exception unreachable) {
            throw new TunnelException("Could not open a tunnel via " + tunnel.host, unreachable);
        }
    }

    /**
     * Offers whichever credential the tunnel carries.
     *
     * <p>A key is tried before a password when both are present: a key is the stronger of the two,
     * and a tunnel carrying both was most likely edited rather than deliberately given a fallback.
     */
    private static void authenticate(ClientSession session, SshTunnel tunnel) {
        if (tunnel.hasPrivateKey()) {
            session.addPublicKeyIdentity(loadKey(tunnel));
        }
        if (tunnel.hasPassword()) {
            session.addPasswordIdentity(tunnel.password);
        }
        if (!tunnel.hasPrivateKey() && !tunnel.hasPassword()) {
            throw new TunnelException(tunnel.name + " has neither a key nor a password");
        }
    }

    private static KeyPair loadKey(SshTunnel tunnel) {
        try {
            Iterable<KeyPair> keys =
                    SecurityUtils.loadKeyPairIdentities(
                            null,
                            null,
                            new ByteArrayInputStream(
                                    tunnel.privateKey.getBytes(StandardCharsets.UTF_8)),
                            tunnel.passphrase == null
                                    ? null
                                    : (session, resource, index) -> tunnel.passphrase);
            java.util.Iterator<KeyPair> found = keys == null ? null : keys.iterator();
            if (found == null || !found.hasNext()) {
                throw new TunnelException("The stored private key could not be read");
            }
            return found.next();
        } catch (TunnelException already) {
            throw already;
        } catch (Exception unreadable) {
            throw new TunnelException("The stored private key could not be read", unreadable);
        }
    }

    // --- Host keys ---------------------------------------------------------

    /** What the row pins, carried in the connection context so the verifier can reach it. */
    private static final AttributeRepository.AttributeKey<String> EXPECTED =
            new AttributeRepository.AttributeKey<>();

    /** What the jump host actually presented, so a check can offer it to be pinned. */
    private static final AttributeRepository.AttributeKey<String> PRESENTED =
            new AttributeRepository.AttributeKey<>();

    /**
     * Whether the key the jump host presented is the one it is supposed to present.
     *
     * <p>A tunnel that pins nothing accepts anything, which is what these did before they were
     * rows. That is not good and the interface says so: everything Keydra holds for everything
     * behind a jump host goes through it, so anything that can answer on its address can have all
     * of it. Pinning is a copy and a save, and the check offers the fingerprint to copy.
     */
    private static boolean verify(ClientSession session, PublicKey key) {
        String fingerprint = KeyUtils.getFingerPrint(key);
        session.setAttribute(PRESENTED, fingerprint);
        // From the connection context rather than from the session: the session's own
        // attributes cannot have been set yet — this runs during the handshake that creates it.
        AttributeRepository context = session.getConnectionContext();
        String expected = context == null ? null : context.getAttribute(EXPECTED);
        if (expected == null || expected.isBlank()) {
            return true;
        }
        if (expected.trim().equalsIgnoreCase(fingerprint)) {
            return true;
        }
        throw new TunnelException(
                "The host key changed: expected "
                        + expected.trim()
                        + " and got "
                        + fingerprint
                        + ". Either the jump host was rebuilt, or something else is answering on"
                        + " its address.");
    }

    /**
     * How many SSH sessions this instance is holding open.
     *
     * <p>Long work like a migration is long work: a tunnel is somebody else's machine holding a
     * connection for us, and it goes when this instance does.
     */
    @Override
    public Snapshot snapshot() {
        return Snapshot.ofJobs(sessions.size());
    }
}
