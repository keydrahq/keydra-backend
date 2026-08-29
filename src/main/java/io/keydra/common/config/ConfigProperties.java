package io.keydra.common.config;

/**
 * Central registry of configuration and build-metadata keys.
 *
 * <p>Mirrors Cryostat's {@code ConfigProperties} convention: string literals for keys never appear
 * scattered across the codebase, they are declared once here.
 */
public final class ConfigProperties {

    private ConfigProperties() {}

    /** Classpath resource holding Maven-filtered build metadata. */
    public static final String BUILD_PROPERTIES_RESOURCE = "/build.properties";

    public static final String BUILD_NAME = "keydra.name";
    public static final String BUILD_VERSION = "keydra.version";
    public static final String BUILD_TIMESTAMP = "keydra.build.timestamp";
    public static final String BUILD_COMMIT = "keydra.build.commit";
    public static final String BUILD_JAVA_VERSION = "keydra.build.java-version";
    public static final String BUILD_QUARKUS_VERSION = "keydra.build.quarkus-version";
}
