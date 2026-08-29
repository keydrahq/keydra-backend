package io.keydra.security;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Turns enforcement on for a test class.
 *
 * <p>The suite runs with security off, because a test about what the key browser does should not
 * have to authenticate in order to say it. A test about <em>who may</em> use the key browser must,
 * so it asks for this profile — which costs one extra application start and buys an unauthenticated
 * request that is genuinely refused rather than quietly granted every role.
 */
public class SecuredProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of("keydra.security.enabled", "true");
    }
}
