package io.keydra.authz.service;

import io.keydra.authz.entity.AppUser;
import io.keydra.authz.entity.BuiltInRole;
import io.keydra.authz.entity.Grant;
import io.keydra.authz.entity.ScopeType;
import io.keydra.authz.entity.SubjectType;
import io.keydra.authz.persistence.AuthzRepository;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Creates an administrator on a development instance that has none.
 *
 * <p>Enforcement is on in dev, which is right — a security model nobody exercises is a security
 * model nobody has tested — but it means every fresh database starts at a login page with no
 * account to use. Somebody would have to click through the first-run setup before they could look
 * at a key, several times a week.
 *
 * <p>The password is configuration and the property is set only in the dev profile, so there is no
 * value for this to fall back to anywhere else: a production instance has nothing to seed and this
 * does nothing at all. That is deliberate — the one thing worse than no default administrator is a
 * documented one on a machine somebody can reach.
 *
 * <p>Runs only when there are no accounts, so it never touches an instance somebody has configured
 * and never resets a password that has been changed.
 */
@ApplicationScoped
public class DevAdministratorSeeder {

    private static final Logger LOG = Logger.getLogger(DevAdministratorSeeder.class);

    private final AuthzRepository repository;
    private final PasswordHasher hasher;
    private final Optional<String> username;
    private final Optional<String> password;

    @Inject
    DevAdministratorSeeder(
            AuthzRepository repository,
            PasswordHasher hasher,
            @ConfigProperty(name = "keydra.security.dev-administrator.username")
                    Optional<String> username,
            @ConfigProperty(name = "keydra.security.dev-administrator.password")
                    Optional<String> password) {
        this.repository = repository;
        this.hasher = hasher;
        this.username = username;
        this.password = password;
    }

    void onStart(@Observes StartupEvent ignored) {
        if (username.isEmpty() || password.isEmpty()) {
            return;
        }
        try {
            // Awaited for the same reason the role seeder is: a request arriving while this was
            // still being written would meet an instance that says it needs setting up.
            VertxContextSupport.subscribeAndAwait(this::seed);
        } catch (Throwable failure) {
            LOG.error("Could not create the development administrator", failure);
        }
    }

    @WithTransaction
    public Uni<Boolean> seed() {
        return repository
                .countUsers()
                .flatMap(
                        count -> {
                            if (count > 0) {
                                return Uni.createFrom().item(false);
                            }
                            AppUser user = new AppUser();
                            user.username = username.orElseThrow();
                            user.displayName = "Development administrator";
                            user.provider = "local";
                            user.enabled = true;
                            user.passwordHash = hasher.hash(password.orElseThrow());

                            return repository
                                    .save(user)
                                    .flatMap(saved -> grantEverything(saved))
                                    .invoke(
                                            () ->
                                                    LOG.infof(
                                                            "Created the development administrator"
                                                                + " '%s'. This happens only where"
                                                                + " keydra.security.dev-administrator"
                                                                + " is configured, which is the dev"
                                                                + " profile.",
                                                            user.username))
                                    .replaceWith(true);
                        });
    }

    private Uni<Void> grantEverything(AppUser user) {
        return repository
                .roleByName(BuiltInRole.ADMIN.id())
                .flatMap(
                        role -> {
                            if (role == null) {
                                LOG.warn("The built-in roles are not seeded yet; no grant made");
                                return Uni.createFrom().voidItem();
                            }
                            Grant grant = new Grant();
                            grant.subjectType = SubjectType.USER;
                            grant.subjectId = user.id;
                            grant.scopeType = ScopeType.INSTANCE;
                            grant.roleId = role.id;
                            grant.grantedBy = "dev-seed";
                            return repository.save(grant).replaceWithVoid();
                        });
    }
}
