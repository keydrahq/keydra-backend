package io.keydra.authz.service;

import io.keydra.authz.entity.BuiltInRole;
import io.keydra.authz.entity.RoleDefinition;
import io.keydra.authz.persistence.AuthzRepository;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.HashSet;
import org.jboss.logging.Logger;

/**
 * Makes sure the three built-in roles exist as rows.
 *
 * <p>A grant points at a role row like any other, so the built-in ones need to be there before
 * anybody can be granted one. Seeded at startup rather than in the migration because what each
 * carries is defined in {@link BuiltInRole} — a migration would have to restate it, and the two
 * would drift the first time a permission was added.
 *
 * <p>Rewritten on every start for the same reason: adding a permission to {@code operator} in code
 * has to reach the row, or the code would say one thing and the database another.
 */
@ApplicationScoped
public class BuiltInRoleSeeder {

    private static final Logger LOG = Logger.getLogger(BuiltInRoleSeeder.class);

    private final AuthzRepository repository;

    @Inject
    BuiltInRoleSeeder(AuthzRepository repository) {
        this.repository = repository;
    }

    /**
     * Seeds before anything can ask for a role.
     *
     * <p>Awaited rather than fired and forgotten: a request arriving while the rows were still
     * being written would resolve to no permissions, which looks exactly like a correct refusal. It
     * is three rows, and it happens once.
     *
     * <p>Run through VertxContextSupport because a startup observer has no Vert.x context of its
     * own, and the reactive session needs one.
     */
    void onStart(@Observes StartupEvent ignored) {
        try {
            VertxContextSupport.subscribeAndAwait(this::seed);
        } catch (Throwable failure) {
            LOG.error("Could not seed the built-in roles", failure);
        }
    }

    @WithTransaction
    public Uni<Integer> seed() {
        Uni<Integer> seeded = Uni.createFrom().item(0);
        for (BuiltInRole role : BuiltInRole.values()) {
            seeded = seeded.flatMap(count -> ensure(role).map(ignored -> count + 1));
        }
        return seeded;
    }

    private Uni<RoleDefinition> ensure(BuiltInRole role) {
        return repository
                .roleByName(role.id())
                .flatMap(
                        existing -> {
                            if (existing == null) {
                                RoleDefinition definition = new RoleDefinition();
                                definition.name = role.id();
                                definition.builtIn = true;
                                definition.description = "Built in; cannot be edited";
                                definition.permissions = new HashSet<>(role.permissions());
                                return repository.save(definition);
                            }
                            // What the role carries lives in code, so the row follows it.
                            existing.builtIn = true;
                            existing.permissions = new HashSet<>(role.permissions());
                            return Uni.createFrom().item(existing);
                        });
    }
}
