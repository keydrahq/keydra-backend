package io.keydra.cluster.service;

import java.util.UUID;
import org.eclipse.microprofile.config.ConfigProvider;

/**
 * What this process calls itself, decided once and available before anything is running.
 *
 * <p>Static, deliberately, and this is the reason: the instance is a label on every metric, and the
 * meter filter that applies it is consulted while Vert.x itself is being built — before the bean
 * container will hand anything out. A bean asked at that moment fails with "synthetic bean not
 * initialized yet", which is a startup that does not happen rather than a metric that is missing.
 *
 * <p>Immutable for the life of the process, because it is an identity: something that changed while
 * running would split every series in two and make the lease look like two instances.
 */
public final class InstanceId {

    private static final String ID = decide();

    private InstanceId() {}

    public static String get() {
        return ID;
    }

    /**
     * The configured name, or one made from where this is running.
     *
     * <p>The host the platform says so — a pod name is the thing an operator would search for — and
     * something random after it, because two instances on one host must not answer to the same
     * name.
     */
    private static String decide() {
        String configured =
                ConfigProvider.getConfig()
                        .getOptionalValue("keydra.cluster.instance-id", String.class)
                        .filter(named -> !named.isBlank())
                        .orElse(null);
        if (configured != null) {
            return trimmed(configured);
        }
        String host = System.getenv("HOSTNAME");
        String where = host == null || host.isBlank() ? "keydra" : host;
        return trimmed(where + "-" + UUID.randomUUID().toString().substring(0, 8));
    }

    /** The column holds 64 characters; the end of a name is the part that distinguishes it. */
    private static String trimmed(String id) {
        return id.length() <= 64 ? id : id.substring(id.length() - 64);
    }
}
