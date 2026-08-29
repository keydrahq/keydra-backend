package io.keydra.tunnels.service;

import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.tunnels.TunnelEndpoint;
import io.keydra.tunnels.exception.TunnelException;
import io.keydra.tunnels.persistence.TunnelRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Where to connect to reach something, tunnel or no tunnel.
 *
 * <p>The one thing everything else asks. A caller hands over what it wants to reach and gets back
 * an address; whether that address is the real one or a local forward is not its business, which is
 * what lets the same code path serve a target, a backup destination and whatever is next.
 */
@ApplicationScoped
public class TunnelAccess {

    private final TunnelRepository repository;
    private final TunnelManager tunnels;

    @Inject
    TunnelAccess(TunnelRepository repository, TunnelManager tunnels) {
        this.repository = repository;
        this.tunnels = tunnels;
    }

    /** Where to connect to reach this target. */
    public Uni<TunnelEndpoint> endpointFor(ConnectionProfile profile) {
        return endpointFor(profile.tunnelId, profile.host, profile.port);
    }

    /**
     * Where to connect to reach {@code host:port}, through the given tunnel or directly.
     *
     * <p>Answers immediately when there is no tunnel, so something that does not use one pays
     * nothing for the feature existing.
     */
    public Uni<TunnelEndpoint> endpointFor(Long tunnelId, String host, int port) {
        if (tunnelId == null) {
            return Uni.createFrom().item(TunnelEndpoint.direct(host, port));
        }
        return repository
                .forUse(tunnelId)
                .onItem()
                .ifNull()
                .failWith(
                        () ->
                                new TunnelException(
                                        "The tunnel this is reached through has been removed"))
                .flatMap(tunnel -> tunnels.forwardTo(tunnel, host, port));
    }
}
