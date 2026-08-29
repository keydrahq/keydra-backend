package io.keydra.tunnels;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.vertx.VertxContextSupport;

/** Empties the tunnel table so each test starts from a known state. */
public final class TunnelFixtures {

    private TunnelFixtures() {}

    public static void deleteAll() {
        try {
            VertxContextSupport.subscribeAndAwait(
                    () ->
                            Panache.withTransaction(
                                    () ->
                                            Panache.getSession()
                                                    .flatMap(
                                                            session ->
                                                                    session.createQuery(
                                                                                    "delete from"
                                                                                        + " SshTunnel")
                                                                            .executeUpdate())));
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not clear the tunnels", failure);
        }
    }
}
