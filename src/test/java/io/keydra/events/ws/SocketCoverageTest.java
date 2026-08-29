package io.keydra.events.ws;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

import io.quarkus.websockets.next.WebSocket;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every socket says who may open it.
 *
 * <p>This test exists because two of them did not. The notification socket pushed a description of
 * somebody's estate — which targets exist, which keys changed, what every watched server is
 * reading, what an alert said — to anybody who connected. The console socket ran commands against
 * any target, over a transport with no check at all, while the REST half of the same feature asked
 * for a role *and* a permission before it would show you the history of those commands.
 *
 * <p>Neither was a wrong annotation. Both were a missing one, which is the only way this fails: an
 * endpoint written in a class that nothing else in the codebase resembles, reviewed by somebody
 * reading what it does rather than what it does not say. So it is checked mechanically, against the
 * compiled classes, where forgetting is not possible.
 */
class SocketCoverageTest {

    /**
     * Sockets that are deliberately open, and why.
     *
     * <p>Empty. Every socket in Keydra carries something about somebody's servers, and a socket
     * that did not would still be a socket somebody could hold open.
     */
    private static final List<String> OPEN = List.of();

    @Test
    void everySocketNamesWhoMayOpenIt() {
        List<String> unguarded =
                sockets().stream()
                        .filter(type -> !isGuarded(type))
                        .map(Class::getName)
                        .filter(name -> !OPEN.contains(name))
                        .toList();

        assertThat(unguarded, is(empty()));
    }

    @Test
    void thereAreSocketsToCheck() {
        // A walk that found nothing would pass the test above while proving nothing.
        assertThat(sockets(), hasSize(greaterThan(2)));
    }

    /**
     * Whether opening this socket is checked.
     *
     * <p>The class-level annotation is what matters: a socket is authorised when it is opened, not
     * per frame, because a connection somebody may not have is one they must not be holding. What
     * an individual message additionally requires — the console asks for a permission on the target
     * in the path — is checked further in.
     */
    private static boolean isGuarded(Class<?> socket) {
        return socket.isAnnotationPresent(RolesAllowed.class)
                || socket.isAnnotationPresent(PermitAll.class);
    }

    private static List<Class<?>> sockets() {
        Path classes = Path.of("target", "classes");
        try (Stream<Path> files = Files.walk(classes)) {
            return files.filter(file -> file.toString().endsWith(".class"))
                    .map(file -> classes.relativize(file).toString())
                    .map(name -> name.replace(java.io.File.separatorChar, '.'))
                    .map(name -> name.substring(0, name.length() - ".class".length()))
                    .map(SocketCoverageTest::load)
                    .filter(java.util.Objects::nonNull)
                    .filter(type -> type.isAnnotationPresent(WebSocket.class))
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, SocketCoverageTest.class.getClassLoader());
        } catch (Throwable notLoadable) {
            return null;
        }
    }
}
