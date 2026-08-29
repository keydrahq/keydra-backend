package io.keydra.authz.graphql;

import io.keydra.authz.dto.AuthzDtos.AuthState;
import io.keydra.authz.dto.ProviderDtos.SignInOption;
import io.keydra.authz.service.AuthzAdminService;
import io.keydra.authz.service.LocalIdentities;
import io.keydra.authz.service.ProviderAdminService;
import io.keydra.common.graphql.OneAtATime;
import io.keydra.security.service.SecuritySettings;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.PermitAll;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Query;

/**
 * The two questions the login page asks before anybody has signed in.
 *
 * <p>These are the only operations in the schema open to somebody who is nobody, and that is a
 * decision rather than an oversight — everything else Keydra exposes is about somebody's servers.
 * What these two answer is what this instance expects of a stranger: whether there is anything to
 * sign into at all, whether it needs its first administrator, and which providers it offers. A
 * login page cannot draw itself without them, and it cannot authenticate to ask.
 *
 * <p>Being open here means the GraphQL endpoint answers an anonymous caller, so it is worth being
 * plain about what still holds when it does. The depth, complexity and parser limits in {@code
 * application.properties} are not per-caller. Introspection is off in production. Every other
 * operation carries its own guard, and {@code GraphQLCoverageTest} fails the build for one that
 * does not — so an anonymous query naming any of them is refused rather than answered.
 *
 * <p>Neither leaks anything a sign-in page does not already show. The state says whether
 * enforcement is on; the providers are the buttons underneath the form.
 */
@GraphQLApi
@OneAtATime
public class SignInQueries {

    private final SecuritySettings settings;
    private final SecurityIdentity identity;
    private final AuthzAdminService accounts;
    private final ProviderAdminService providers;

    @Inject
    SignInQueries(
            SecuritySettings settings,
            SecurityIdentity identity,
            AuthzAdminService accounts,
            ProviderAdminService providers) {
        this.settings = settings;
        this.identity = identity;
        this.accounts = accounts;
        this.providers = providers;
    }

    /**
     * Whether there is anything to sign into, and whether anybody has.
     *
     * <p>An instance with enforcement off has no sign-in at all and says so, which is what stops an
     * open instance from looking like a secured one. One with enforcement on and no accounts needs
     * its first administrator before it has a sign-in to offer.
     */
    @Query("authState")
    @Description("What this instance expects of whoever is asking")
    @PermitAll
    public Uni<AuthState> authState() {
        if (!settings.enabled()) {
            return Uni.createFrom().item(new AuthState(false, false, true, name(), false));
        }
        return accounts.hasAccounts()
                .map(
                        any ->
                                new AuthState(
                                        true,
                                        !any,
                                        !identity.isAnonymous(),
                                        name(),
                                        owesAFactor()));
    }

    /**
     * Whether this session may do nothing but pair an authenticator.
     *
     * <p>Read off the identity, which is where it was worked out. The server has already taken the
     * roles away; this is only so the browser can say why, and it is on this query because this is
     * the one question everything else waits on.
     */
    private boolean owesAFactor() {
        return identity != null
                && Boolean.TRUE.equals(identity.getAttribute(LocalIdentities.OWES_A_FACTOR));
    }

    /** The providers this instance offers, as the buttons under the form. */
    @Query("signInOptions")
    @Description("The ways of signing in this instance offers, other than a password here")
    @PermitAll
    public Uni<List<SignInOption>> signInOptions() {
        return providers.options();
    }

    private String name() {
        return identity == null || identity.isAnonymous() || identity.getPrincipal() == null
                ? "anonymous"
                : identity.getPrincipal().getName();
    }
}
