package io.keydra.engine;

/**
 * A store this build cannot serve.
 *
 * <p>Distinct from a target being unreachable, and from a type nobody has implemented: the
 * implementation exists, and this image was built without it. TiKV is behind a Maven profile
 * because its client is an uber-jar that carries forty-nine advisories in bundled copies nothing
 * can upgrade, and an installation that manages no TiKV was carrying all of it for nothing.
 *
 * <p>It is a runtime exception because a connection profile can name a store the running build does
 * not have — the database outlives any one image, so a row written by a build with TiKV can be read
 * by one without.
 */
public class EngineNotBuiltInException extends RuntimeException {

    private final EngineType type;

    public EngineNotBuiltInException(EngineType type) {
        super(
                "This build of Keydra does not include the "
                        + type
                        + " engine. It is built with the `tikv` Maven profile"
                        + " (./mvnw -Ptikv), and the published image does not use it.");
        this.type = type;
    }

    public EngineType type() {
        return type;
    }
}
