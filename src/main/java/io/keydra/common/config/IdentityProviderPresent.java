package io.keydra.common.config;

import java.util.Map;
import java.util.Set;
import org.eclipse.microprofile.config.spi.ConfigSource;

/**
 * Turns the identity provider on when a deployment has named one.
 *
 * <p>Until this existed, the provider followed the enforcement switch, and the reason was written
 * down at the time: <em>"a deployment that is not checking who you are has no use for an identity
 * provider, and one that is has no use without it"</em>. Half of that stopped being true at phase
 * 22, which gave Keydra accounts of its own — passwords it hashes, sessions it issues, second
 * factors, invitations. A deployment can now check who you are perfectly well without a provider
 * anywhere.
 *
 * <p>What the coupling did was refuse to start. Enforcement on and no provider named leaves
 * Quarkus's OIDC tenant enabled with no issuer, and the process dies on {@code
 * 'quarkus.oidc.auth-server-url' property must be configured} — a message about a property nobody
 * set, on a deployment that wanted local accounts and never asked for OIDC at all. Since
 * enforcement is on by default, that was the shipped default failing to boot.
 *
 * <p>So the two are separated, and the provider follows the only thing that actually decides it:
 * whether there is an issuer to talk to. A second environment variable would have been the obvious
 * fix and the wrong one — it is the same fact said twice, and the failure mode is somebody setting
 * one of them.
 *
 * <p>An explicit {@code QUARKUS_OIDC_TENANT_ENABLED} still wins: the ordinal here is below the
 * environment's, so this is a default rather than a decision taken away.
 */
public class IdentityProviderPresent implements ConfigSource {

    private static final String TENANT_ENABLED = "quarkus.oidc.tenant-enabled";

    /** Below an environment variable (300) and a properties file (250), above nothing else. */
    private static final int ORDINAL = 200;

    /** What a deployment names its issuer with, read from the environment as it is set there. */
    private final boolean named;

    public IdentityProviderPresent() {
        String url = System.getenv("KEYDRA_OIDC_URL");
        this.named = url != null && !url.isBlank();
    }

    @Override
    public Set<String> getPropertyNames() {
        return Set.of(TENANT_ENABLED);
    }

    @Override
    public String getValue(String propertyName) {
        return TENANT_ENABLED.equals(propertyName) ? String.valueOf(named) : null;
    }

    @Override
    public Map<String, String> getProperties() {
        return Map.of(TENANT_ENABLED, String.valueOf(named));
    }

    @Override
    public int getOrdinal() {
        return ORDINAL;
    }

    @Override
    public String getName() {
        return "keydra-identity-provider-present";
    }
}
