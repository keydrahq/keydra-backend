package io.keydra.authz.service;

import io.vertx.ext.web.RoutingContext;

/**
 * What a request says about where it came from.
 *
 * <p>Two of these three are kept and one is not. The network and the browser go into a row; the
 * address itself is used to ask which country this is and is then dropped. That asymmetry is the
 * whole design: a country is a fact about a sign-in, and an address is a record of a person's
 * whereabouts, and only one of those is worth keeping to notice that a password has been stolen.
 *
 * <p>All of it is what the client said, and behind a proxy it is what the proxy said. Whether the
 * forwarded headers are believed is configuration ({@code quarkus.http.proxy.*}) rather than
 * something decided here — with nothing in front of Keydra, believing them would let anybody claim
 * any address, which is worse than not asking.
 *
 * @param network the address with its last part removed, or null when there is no address
 * @param address the address in full — for asking a question, never for storing
 * @param userAgent the browser as it described itself, truncated, or null
 */
public record ClientOrigin(String network, String address, String userAgent) {

    private static final ClientOrigin UNKNOWN = new ClientOrigin(null, null, null);

    public static ClientOrigin of(RoutingContext context) {
        if (context == null) {
            return UNKNOWN;
        }
        return new ClientOrigin(networkOf(context), addressOf(context), agentOf(context));
    }

    private static String addressOf(RoutingContext context) {
        if (context.request().remoteAddress() == null) {
            return null;
        }
        String host = context.request().remoteAddress().host();
        return host == null || host.isBlank() ? null : host;
    }

    /** The browser as it described itself, truncated. It is not evidence, only a label. */
    private static String agentOf(RoutingContext context) {
        String agent = context.request().getHeader("User-Agent");
        if (agent == null || agent.isBlank()) {
            return null;
        }
        return agent.length() > 400 ? agent.substring(0, 400) : agent;
    }

    /**
     * The network an address belongs to, not the address.
     *
     * <p>Enough for somebody to say "that is not where I work" and not enough to be a record of
     * their movements — which is what a full address in a table nobody prunes becomes.
     */
    public static String networkOf(RoutingContext context) {
        String address = context == null ? null : addressOf(context);
        return maskOf(address);
    }

    /** The same masking, for an address already in hand. */
    public static String maskOf(String address) {
        if (address == null || address.isBlank()) {
            return null;
        }
        if (address.contains(":")) {
            String[] groups = address.split(":");
            return groups.length >= 4
                    ? String.join(":", groups[0], groups[1], groups[2], groups[3]) + "::"
                    : address;
        }
        String[] parts = address.split("\\.");
        return parts.length == 4 ? String.join(".", parts[0], parts[1], parts[2], "0") : address;
    }
}
