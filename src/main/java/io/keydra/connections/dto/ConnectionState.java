package io.keydra.connections.dto;

/** Lifecycle state the registry tracks per profile. */
public enum ConnectionState {
    /** Never contacted since startup. */
    UNKNOWN,
    /** A connection attempt is in flight. */
    CONNECTING,
    /** Last health check succeeded. */
    UP,
    /** Last health check failed; {@code message} carries the reason. */
    DOWN
}
