package io.keydra.common.config;

import java.util.List;
import org.eclipse.microprofile.config.spi.ConfigSource;
import org.eclipse.microprofile.config.spi.ConfigSourceProvider;

/**
 * Touches the TiKV client's shaded collections once, before anything else can race for them.
 *
 * <p>It supplies no configuration, and that is not a trick — it is the earliest hook this
 * application has. Config sources are discovered while the runtime configuration is built, which is
 * the first thing {@code ApplicationImpl.<clinit>} does; everything else Keydra can observe happens
 * after that.
 *
 * <p><b>Why it has to be that early.</b> The TiKV client shades Guava under {@code org.tikv.shade},
 * and {@code ImmutableList}'s own static initializer builds an iterator over the empty list it is
 * halfway through creating. Java's rule for a class already being initialized on the same thread is
 * to proceed rather than deadlock, so whichever class in that shaded set is touched first decides
 * whether the iterator gets a field that is still null. When it does, startup dies with {@code
 * Cannot invoke ImmutableList.size() because "list" is null}.
 *
 * <p>Phase 55 saw this and put the pre-load on {@code StartupEvent}, which is runtime
 * initialisation. The failure that kept happening is in a <em>static</em> initialisation recorder —
 * Hibernate Validator's, inside {@code ApplicationImpl}'s own {@code <clinit>} — which runs
 * strictly earlier, so that precaution could never reach this path. Phase 59 saw it take out an
 * entire suite run: it struck the first test, and every {@code @QuarkusTest} after it was skipped.
 *
 * <p><b>What is not claimed.</b> This is a precaution against somebody else's initializer, for a
 * race that appears about one boot in ten and cannot be reproduced on demand. That it runs earlier
 * than the failure is a fact; that it is early enough is a reasonable belief and not a measurement.
 * It fails quietly for the same reason phase 55's did: an installation with no TiKV target should
 * not refuse to start because a precaution it does not need could not be taken.
 */
public class ShadedCollectionsFirst implements ConfigSourceProvider {

    static {
        try {
            Class.forName(
                    "org.tikv.shade.com.google.common.collect.ImmutableList",
                    true,
                    ShadedCollectionsFirst.class.getClassLoader());
        } catch (Throwable notThere) {
            // No TiKV on the classpath, which is most installations. Nothing to do and nothing to
            // say: this is not a configuration problem and the log is not the place for it.
        }
    }

    /**
     * The one source this provider does supply, which has nothing to do with the rest of the class.
     *
     * <p>Two unrelated things in one file because there is one hook: a provider is discovered by
     * name from {@code META-INF/services}, and a second entry there would be a second class doing
     * the same discovery for the same reason. What each of them is for is on its own type.
     */
    @Override
    public Iterable<ConfigSource> getConfigSources(ClassLoader classLoader) {
        return List.of(new IdentityProviderPresent());
    }
}
