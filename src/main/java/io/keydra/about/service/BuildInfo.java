package io.keydra.about.service;

import io.keydra.common.config.ConfigProperties;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Build-time metadata, read once from the Maven-filtered {@code build.properties} on the classpath.
 *
 * <p>Every value has an {@code unknown} fallback so that a checkout without git history (or a
 * shallow CI clone) still produces a well-formed response instead of failing startup.
 */
@ApplicationScoped
public class BuildInfo {

    private static final String UNKNOWN = "unknown";

    private String name;
    private String version;
    private String timestamp;
    private String commit;
    private String javaVersion;
    private String quarkusVersion;

    @PostConstruct
    void load() {
        Properties props = new Properties();
        try (InputStream in =
                BuildInfo.class.getResourceAsStream(ConfigProperties.BUILD_PROPERTIES_RESOURCE)) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(
                    "Unable to read " + ConfigProperties.BUILD_PROPERTIES_RESOURCE, e);
        }
        this.name = read(props, ConfigProperties.BUILD_NAME);
        this.version = read(props, ConfigProperties.BUILD_VERSION);
        this.timestamp = read(props, ConfigProperties.BUILD_TIMESTAMP);
        this.commit = read(props, ConfigProperties.BUILD_COMMIT);
        this.javaVersion = read(props, ConfigProperties.BUILD_JAVA_VERSION);
        this.quarkusVersion = read(props, ConfigProperties.BUILD_QUARKUS_VERSION);
    }

    /** Returns the property value, mapping absent and unsubstituted placeholders to "unknown". */
    private static String read(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null || value.isBlank() || value.startsWith("${")) {
            return UNKNOWN;
        }
        return value;
    }

    public String name() {
        return name;
    }

    public String version() {
        return version;
    }

    public String timestamp() {
        return timestamp;
    }

    public String commit() {
        return commit;
    }

    public String javaVersion() {
        return javaVersion;
    }

    public String quarkusVersion() {
        return quarkusVersion;
    }
}
