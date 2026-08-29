package io.keydra.engine.aerospike;

import com.aerospike.client.Host;
import com.aerospike.client.async.EventPolicy;
import com.aerospike.client.async.NettyEventLoops;
import com.aerospike.client.policy.ClientPolicy;
import com.aerospike.client.policy.TlsPolicy;
import com.aerospike.client.reactor.AerospikeReactorClient;
import io.keydra.common.tls.Certificates;
import io.keydra.connections.entity.ConnectionProfile;
import io.quarkus.runtime.ShutdownEvent;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jboss.logging.Logger;

/**
 * One Aerospike client per profile, kept for as long as the profile is used.
 *
 * <p>Held rather than made per request for the reason every driver of this shape is held: a client
 * is a view of a cluster, and building one means discovering every node and opening connections to
 * all of them. Doing that per request would spend more on finding the server than on asking it
 * anything.
 *
 * <p>Its event loops are Keydra's own. The client would otherwise start a thread pool of its own,
 * and an application that has already decided which threads it runs on should not acquire a second
 * opinion about that from a driver — the Netty loops here are the ones Vert.x is already using, so
 * an answer comes back on a thread the rest of this code is expecting to be on.
 */
@ApplicationScoped
public class AerospikeClients {

    private static final Logger LOG = Logger.getLogger(AerospikeClients.class);

    /**
     * What a client is keyed on.
     *
     * <p>The address and the credentials, and not the namespace: one client serves every namespace
     * on a cluster, and keying on the namespace as well would open a second connection to the same
     * servers for a profile that differs only in which part of them it reads.
     */
    /**
     * What makes two profiles the same client.
     *
     * <p>The certificates are part of it. They are baked into the SSL context when the client is
     * built, so a profile whose authority was corrected would otherwise keep dialling with the old
     * one until something dropped the client — and "I fixed the certificate and it still refuses"
     * is the least debuggable version of that.
     *
     * <p>The passphrase for the same reason, one step further along: it decides what the key
     * decrypts to, so a corrected passphrase that did not change this key would be a client still
     * dialling with whatever the wrong one produced.
     *
     * <p>Their fingerprints rather than the text: this record is held in a map and compared on
     * every call, and a key that carried two kilobytes of PEM would be doing that work per
     * operation.
     */
    private record ClientKey(
            String host, int port, String username, boolean tls, int certificates) {

        static ClientKey of(ConnectionProfile profile) {
            return new ClientKey(
                    profile.host,
                    profile.port,
                    profile.username,
                    profile.tls,
                    java.util.Objects.hash(
                            profile.tlsCaCert,
                            profile.tlsClientCert,
                            profile.tlsClientKey,
                            profile.tlsClientKeyPassphrase));
        }
    }

    /** Which profiles each client is serving, so one can be let go by profile id. */
    private final Map<Long, ClientKey> byProfile = new ConcurrentHashMap<>();

    private final Map<ClientKey, AerospikeReactorClient> clients = new ConcurrentHashMap<>();
    private final NettyEventLoops loops;

    @Inject
    AerospikeClients(Vertx vertx) {
        this.loops =
                new NettyEventLoops(
                        new EventPolicy(),
                        ((io.vertx.core.impl.VertxInternal) vertx).getEventLoopGroup());
    }

    public AerospikeReactorClient forProfile(ConnectionProfile profile) {
        ClientKey key = ClientKey.of(profile);
        if (profile.id != null) {
            byProfile.put(profile.id, key);
        }
        return clients.computeIfAbsent(key, ignored -> connect(profile));
    }

    private AerospikeReactorClient connect(ConnectionProfile profile) {
        ClientPolicy policy = new ClientPolicy();
        policy.eventLoops = loops;
        if (profile.username != null && !profile.username.isBlank()) {
            policy.user = profile.username;
            policy.password = profile.password;
        }
        if (profile.tls) {
            // Defaults, deliberately: the point of turning TLS on is that the certificate is
            // checked, and a policy that trusted anything would be the connection somebody
            // believed was safe.
            policy.tlsPolicy = new TlsPolicy();
            // And the certificates this target's TLS actually needs, where it has them: an
            // authority the runtime does not already trust, and the certificate to present when
            // the server asks the client who it is. Aerospike takes a built SSLContext and
            // nothing else, which is the only reason this looks different from the RESP side —
            // the PEM is the same PEM, read by the same class.
            if (Certificates.present(profile.tlsCaCert)
                    || Certificates.present(profile.tlsClientCert)) {
                policy.tlsPolicy.context =
                        Certificates.sslContext(
                                profile.tlsCaCert,
                                profile.tlsClientCert,
                                profile.tlsClientKey,
                                profile.tlsClientKeyPassphrase);
            }
        }
        // Failing here rather than on the first read: a profile pointing at nothing should say so
        // when it is tried, which is what the connection test does.
        policy.failIfNotConnected = true;
        return new AerospikeReactorClient(
                new com.aerospike.client.AerospikeClient(
                        policy, new Host(profile.host, profile.port)));
    }

    /**
     * Drops the client for a profile, which the next use rebuilds.
     *
     * <p>Called when a profile's address or credentials change: a held client is pointed at the
     * host it was built for, and one kept across an edit would go on talking to the old one.
     */
    public void forget(Long profileId) {
        ClientKey key = byProfile.remove(profileId);
        if (key == null) {
            return;
        }
        /*
         * Only when nothing else is on it. Clients are keyed on the address rather than on the
         * profile, so two profiles pointing at one cluster share one — and closing it because one
         * of them was edited would take the connection out from under the other.
         */
        if (byProfile.containsValue(key)) {
            return;
        }
        AerospikeReactorClient closing = clients.remove(key);
        if (closing != null) {
            closing.close();
        }
    }

    void closeAll(@Observes ShutdownEvent shutdown) {
        clients.values().forEach(AerospikeReactorClient::close);
        clients.clear();
        LOG.debug("Closed every Aerospike client");
    }
}
