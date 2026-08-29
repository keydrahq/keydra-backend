package io.keydra.common.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Which addresses Keydra will make a request to when somebody types one into a form.
 *
 * <p>The one that matters is the first. A webhook is the shortest path anybody has from "may
 * configure an alert" — an operator's permission — to "holds this deployment's cloud credentials",
 * because {@code 169.254.169.254} answers unauthenticated with them on every large cloud.
 */
@QuarkusTest
class EgressGuardTest {

    @Inject EgressGuard guard;

    @Test
    void refusesTheAddressWhereCloudMachinesKeepTheirCredentials() {
        assertThrows(
                BlockedAddressException.class,
                () ->
                        guard.checkBlocking(
                                "http://169.254.169.254/latest/meta-data/iam/security-credentials/"));
    }

    @Test
    void refusesEveryLinkLocalAddressRatherThanThatOneString() {
        // The metadata service is not the only thing on link-local, and a check that named one
        // address would be a check somebody walks around with a different one.
        assertFalse(guard.permits("http://169.254.42.7/"));
        assertFalse(guard.permits("http://[fe80::1]/hook"));
    }

    @Test
    void refusesWhatIsNotAnAddressARequestCanBeMadeTo() {
        assertFalse(guard.permits("file:///etc/passwd"));
        assertFalse(guard.permits("gopher://example.com/"));
        assertFalse(guard.permits("http://"));
        assertFalse(guard.permits("not a url at all"));
    }

    @Test
    void allowsAnOrdinaryAddress() {
        // Resolved rather than assumed: this is the case the whole guard has to stay out of the
        // way of, and a check that refused it would be turned off on the first day.
        assertTrue(guard.permits("https://example.com/hooks/keydra"));
    }

    @Test
    void allowsLoopbackWhereConfigurationSaysSo() {
        // The dev and test profiles both say so, because a receiver running beside Keydra is a
        // real arrangement rather than a mistake.
        assertTrue(guard.permits("http://127.0.0.1:9999/hook"));
    }
}
