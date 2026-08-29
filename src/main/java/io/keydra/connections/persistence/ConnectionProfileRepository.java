package io.keydra.connections.persistence;

import io.keydra.connections.entity.ConnectionProfile;
import io.quarkus.hibernate.reactive.panache.PanacheRepository;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * Persistence for connection profiles.
 *
 * <p>The repository pattern rather than active record: queries are ordinary instance methods on an
 * injectable bean, so the entity stays a data model, the service's dependencies stay explicit, and
 * nothing depends on Panache rewriting static calls at build time.
 */
@ApplicationScoped
public class ConnectionProfileRepository implements PanacheRepository<ConnectionProfile> {

    public Uni<ConnectionProfile> findByName(String name) {
        return find("name", name).firstResult();
    }

    /**
     * Every profile's id, and nothing else about it.
     *
     * <p>For the places that need to ask "which of these may they see" before narrowing something
     * larger. The catalogue is a list of servers somebody typed in, so this is tens of rows rather
     * than a table scan, and asking for the ids alone keeps it that way — loading whole profiles,
     * each with an encrypted password to decrypt, to read one column off each would not.
     */
    public Uni<List<Long>> allIds() {
        return find("select id from ConnectionProfile").project(Long.class).list();
    }
}
