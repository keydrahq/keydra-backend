package io.keydra.security.service;

import io.keydra.security.Roles;
import io.quarkus.security.identity.AuthenticationRequestContext;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.identity.SecurityIdentityAugmentor;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Gives every request full rights while enforcement is switched off.
 *
 * <p>Without this, a build with {@code keydra.security.enabled=false} would still have
 * {@code @RolesAllowed} on its endpoints and would refuse every anonymous request — so "security
 * off" would mean "nothing works" rather than "no one is checked".
 *
 * <p>Deliberately does nothing when enforcement is on. It cannot widen the rights of a real
 * identity, only stand in for the absence of one.
 */
@ApplicationScoped
public class OpenAccessAugmentor implements SecurityIdentityAugmentor {

    /** The name shown in the audit log for actions taken while nobody is being identified. */
    public static final String ANONYMOUS = "anonymous";

    private final SecuritySettings settings;

    @Inject
    OpenAccessAugmentor(SecuritySettings settings) {
        this.settings = settings;
    }

    @Override
    public Uni<SecurityIdentity> augment(
            SecurityIdentity identity, AuthenticationRequestContext context) {
        if (settings.enabled() || !identity.isAnonymous()) {
            return Uni.createFrom().item(identity);
        }
        return Uni.createFrom()
                .item(
                        QuarkusSecurityIdentity.builder()
                                .setPrincipal(new QuarkusPrincipal(ANONYMOUS))
                                .addRoles(
                                        java.util.Set.of(Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN))
                                .build());
    }
}
