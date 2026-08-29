package io.keydra.security.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Whether Keydra is enforcing who may do what.
 *
 * <p>Enforcement is on unless configuration says otherwise, and the only place that says otherwise
 * is the dev profile. That direction matters: a deployment that forgets to configure security gets
 * security, not an open door.
 *
 * <p>It is off in dev because the alternative is running an identity provider to browse a local
 * Redis. The frontend asks for this state and adapts, so the difference is visible rather than a
 * surprise when the same build is deployed.
 */
@ApplicationScoped
public class SecuritySettings {

    private final boolean enabled;

    @Inject
    SecuritySettings(
            @ConfigProperty(name = "keydra.security.enabled", defaultValue = "true")
                    boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() {
        return enabled;
    }
}
