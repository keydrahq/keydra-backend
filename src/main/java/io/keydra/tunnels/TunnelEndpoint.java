package io.keydra.tunnels;

/**
 * Where to connect in order to reach a target.
 *
 * <p>Either the target itself, or the local end of a tunnel that leads to it. Everything above this
 * connects to whatever is here and does not need to know which.
 */
public record TunnelEndpoint(String host, int port, boolean tunnelled) {

    /** The target's own address, for a profile that needs no tunnel. */
    public static TunnelEndpoint direct(String host, int port) {
        return new TunnelEndpoint(host, port, false);
    }
}
