package io.keydra.authz.service;

import com.maxmind.geoip2.DatabaseReader;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.File;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Which country an address is in, when this instance has been given a way to know.
 *
 * <p>Optional, and shaped like the other optional capabilities in Keydra: it answers {@code
 * Optional.empty()} rather than throwing, so the checks that want a country carry on without one
 * instead of failing. Nothing here downloads anything — the database is a file an operator puts
 * somewhere and names in configuration, because a management console that reaches out to a third
 * party on every sign-in would be sending them a record of who signs in where.
 *
 * <p>Without a database the anomaly checks still work: a network this account has never used is
 * still a network this account has never used. What is lost is the two that need a map — a new
 * country, and a pair of sign-ins too far apart to be the same person.
 *
 * <p>The address goes in and two letters come out. The address is never written down; see {@link
 * ClientOrigin}.
 */
@ApplicationScoped
public class SignInGeography {

    private static final Logger LOG = Logger.getLogger(SignInGeography.class);

    private final Optional<String> databasePath;
    private DatabaseReader reader;

    @Inject
    SignInGeography(
            @ConfigProperty(name = "keydra.security.geoip-database")
                    Optional<String> databasePath) {
        this.databasePath = databasePath;
    }

    void onStart(@Observes StartupEvent ignored) {
        Optional<Path> file =
                databasePath
                        .filter(path -> !path.isBlank())
                        .map(Path::of)
                        .filter(Files::isReadable);
        if (file.isEmpty()) {
            databasePath
                    .filter(path -> !path.isBlank())
                    .ifPresent(
                            path ->
                                    LOG.warnf(
                                            "No geography database at %s, so sign-ins will not be"
                                                    + " placed in a country. Everything else about"
                                                    + " them is still noticed.",
                                            path));
            return;
        }
        try {
            reader = new DatabaseReader.Builder(new File(file.get().toString())).build();
            LOG.infof("Sign-in geography is on, reading %s", file.get());
        } catch (Exception unreadable) {
            // A malformed database is a misconfiguration, not a reason to refuse to start: the
            // instance signs people in perfectly well without knowing where they are.
            LOG.warnf(
                    "Could not read the geography database at %s (%s), so sign-ins will not be"
                            + " placed in a country.",
                    file.get(), unreadable.getMessage());
        }
    }

    void onStop(@Observes ShutdownEvent ignored) {
        if (reader != null) {
            try {
                reader.close();
            } catch (Exception closing) {
                LOG.debug("Could not close the geography database", closing);
            }
        }
    }

    /** Whether this string is an address rather than a name, without asking a resolver. */
    private static boolean looksNumeric(String address) {
        boolean colons = address.indexOf(':') >= 0;
        for (int index = 0; index < address.length(); index++) {
            char character = address.charAt(index);
            boolean allowed =
                    (character >= '0' && character <= '9')
                            || character == '.'
                            || (colons
                                    && (character == ':'
                                            || (character >= 'a' && character <= 'f')
                                            || (character >= 'A' && character <= 'F')));
            if (!allowed) {
                return false;
            }
        }
        return !address.isEmpty();
    }

    /** Whether this instance can place an address at all. */
    public boolean available() {
        return reader != null;
    }

    /** Two letters, or nothing — for an address outside the database as much as for no database. */
    public Optional<String> countryOf(String address) {
        if (reader == null || address == null || address.isBlank()) {
            return Optional.empty();
        }
        if (!looksNumeric(address)) {
            // getByName resolves anything that is not a literal, and a name lookup is a blocking
            // call on an event loop. Addresses reaching here are always literals; something that
            // is not one is a misconfiguration, and the answer to it is no country rather than a
            // stall.
            return Optional.empty();
        }
        try {
            return reader.tryCountry(InetAddress.getByName(address))
                    .map(response -> response.getCountry().getIsoCode())
                    .filter(code -> code != null && !code.isBlank());
        } catch (Exception unplaceable) {
            // A private address, a hostname that will not resolve, a gap in the database. All of
            // them mean the same thing here and none is worth a line in the log on every sign-in.
            return Optional.empty();
        }
    }
}
