package io.keydra.common.config;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * What a deployment says twice, differently.
 *
 * <p>Plain construction rather than a running application, because what is being checked is the
 * judgement rather than the wiring: each of these is two settings that cannot both be right, and
 * the thing worth pinning is which pairs count and which do not. The one that needs a running
 * application — a request arriving through a proxy — is a boolean here, which is all the rest of
 * the code sees of it.
 */
class DeploymentChecksTest {

    @Test
    void aProxyInFrontOfAnInstanceThatWasNotToldSaysSo() {
        DeploymentChecks checks = settings(behind(true), false, "", "", true, "");
        assertThat(checks.notes(), hasSize(1));
        assertThat(names(checks), contains("KEYDRA_BEHIND_PROXY"));
    }

    @Test
    void anInstanceThatWasToldIsNotWorthMentioning() {
        // Believing a proxy while naming one is the arrangement this is asking for.
        assertThat(settings(behind(true), true, "10.0.0.0/8", "", true, "").notes(), empty());
    }

    @Test
    void believingAnybodyIsWorseThanNotAsking() {
        // The properties file has said so since the day it was written, and had never said it
        // anywhere the person running the thing would read.
        assertThat(
                names(settings(behind(false), true, "", "", true, "")),
                contains("KEYDRA_TRUSTED_PROXIES"));
    }

    @Test
    void anIdentityProviderWithNowhereToComeBackToSaysSo() {
        assertThat(
                names(settings(behind(false), false, "", "", true, "https://issuer.example")),
                contains("KEYDRA_PUBLIC_URL"));
    }

    @Test
    void andWithoutOneItIsNobodysBusiness() {
        // Not "you have not configured this". A deployment that signs people in locally has no
        // redirect URI to agree with anybody, and drawing that as a fault would be inventing one.
        assertThat(settings(behind(false), false, "", "", true, "").notes(), empty());
    }

    @Test
    void aCookieTravellingInClearOnAnHttpsDeploymentSaysSo() {
        assertThat(
                names(settings(behind(false), false, "", "https://keydra.example", false, "")),
                contains("KEYDRA_COOKIE_SECURE"));
    }

    @Test
    void andOnAPlainOneItIsHowSomebodyStaysSignedIn() {
        // A demonstration served over http: the browser would refuse to keep a secure cookie, so
        // turning it off is the arrangement rather than the mistake.
        assertThat(
                settings(behind(false), false, "", "http://localhost:8181", false, "").notes(),
                empty());
    }

    @Test
    void anOrdinaryDeploymentSaysNothingAtAll() {
        assertThat(
                settings(
                                behind(false),
                                true,
                                "10.0.0.0/8",
                                "https://keydra.example",
                                true,
                                "https://issuer.example")
                        .notes(),
                empty());
    }

    private static List<String> names(DeploymentChecks checks) {
        return checks.notes().stream().map(DeploymentNote::setting).toList();
    }

    private static DeploymentChecks settings(
            ProxyObserved proxies,
            boolean forwarding,
            String trusted,
            String publicUrl,
            boolean cookieSecure,
            String oidc) {
        return new DeploymentChecks(
                proxies,
                forwarding,
                Optional.of(trusted),
                Optional.of(publicUrl),
                cookieSecure,
                Optional.of(oidc));
    }

    /** The one fact here that comes from traffic rather than from configuration. */
    private static ProxyObserved behind(boolean seen) {
        return new ProxyObserved() {
            @Override
            public boolean behindOne() {
                return seen;
            }
        };
    }
}
