package io.keydra.tunnels.persistence;

import io.keydra.tunnels.entity.SshTunnel;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/** Reads and writes the jump hosts. */
@ApplicationScoped
public class TunnelRepository {

    public Uni<List<SshTunnel>> all() {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery("from SshTunnel order by name", SshTunnel.class)
                                        .getResultList());
    }

    public Uni<SshTunnel> byId(Long id) {
        return Panache.getSession().flatMap(session -> session.find(SshTunnel.class, id));
    }

    /**
     * One tunnel, read in a session of its own.
     *
     * <p>Opening a tunnel is a TCP connection, a key exchange and an authentication, none of which
     * should happen with a database session held open behind it.
     */
    @WithSession
    public Uni<SshTunnel> forUse(Long id) {
        return byId(id);
    }

    public Uni<SshTunnel> byName(String name) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "from SshTunnel where name = :name",
                                                SshTunnel.class)
                                        .setParameter("name", name)
                                        .getSingleResultOrNull());
    }

    public Uni<SshTunnel> save(SshTunnel tunnel) {
        return Panache.getSession().flatMap(session -> session.persist(tunnel).replaceWith(tunnel));
    }

    /** How many targets and destinations point at a tunnel, so removing one can say. */
    public Uni<Long> usageOf(Long id) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "select (select count(p) from ConnectionProfile p"
                                                    + " where p.tunnelId = :id) + (select count(d)"
                                                    + " from BackupDestination d where d.tunnelId ="
                                                    + " :id)",
                                                Long.class)
                                        .setParameter("id", id)
                                        .getSingleResult());
    }

    public Uni<Boolean> delete(Long id) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery("delete from SshTunnel where id = :id")
                                        .setParameter("id", id)
                                        .executeUpdate())
                .map(deleted -> deleted > 0);
    }
}
