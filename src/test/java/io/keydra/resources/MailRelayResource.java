package io.keydra.resources;

import io.keydra.alerts.FakeSmtp;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import java.io.IOException;
import java.util.Map;

/**
 * The relay this instance sends its own mail through, and somewhere for it to land.
 *
 * <p>An alert delivery carries its own host and port in a row, so a test can start a catcher and
 * point one at it. The instance's relay is configuration, which has to exist before the application
 * starts — so it arrives the way the SSH server does, as a resource that starts a listener on an
 * ephemeral port and hands the port back as a setting.
 *
 * <p>It configures a public URL too, because a letter with no address to put in its button is not
 * sent at all. Both are restricted to the class that asks for them: a public URL is what a sign-in
 * redirect is compared against, and turning one on for the whole run would be changing what a dozen
 * tests about identity providers are testing.
 */
public class MailRelayResource implements QuarkusTestResourceLifecycleManager {

    /** Where Keydra appears to write from, and the host the letters name at the bottom. */
    public static final String FROM = "keydra@example.test";

    public static final String PUBLIC_URL = "https://keydra.example.test";

    private static volatile FakeSmtp relay;

    /** What has arrived, for a test to read and to empty between cases. */
    public static FakeSmtp relay() {
        return relay;
    }

    @Override
    public Map<String, String> start() {
        try {
            relay = new FakeSmtp();
            return Map.of(
                    "keydra.mail.host",
                    "127.0.0.1",
                    "keydra.mail.port",
                    String.valueOf(relay.port()),
                    "keydra.mail.tls",
                    "false",
                    "keydra.mail.from",
                    FROM,
                    "keydra.public-url",
                    PUBLIC_URL);
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the test mail relay", e);
        }
    }

    @Override
    public void stop() {
        if (relay != null) {
            relay.close();
            relay = null;
        }
    }
}
