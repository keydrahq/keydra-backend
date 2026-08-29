package io.keydra.security;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;

import io.keydra.connections.persistence.EncryptedStringConverter;
import io.keydra.security.service.SecretRotation;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every encrypted column is one the rotation moves.
 *
 * <p>The failure this exists for is silent: a rotation that skips a column leaves a credential
 * under a key somebody is about to delete, and nothing says so until that value stops decrypting
 * weeks later. It has already happened once — a column was added to an entity and not to the list —
 * which is why the list is checked against the entities rather than against a memory.
 *
 * <p>Walks the compiled classes rather than starting the application, so it is fast enough to be
 * the thing that fails first when somebody adds a secret.
 */
class SecretCoverageTest {

    @Test
    void everyEncryptedColumnIsRotated() {
        List<String> covered = SecretRotation.covers();

        List<String> missing =
                entities().stream()
                        .flatMap(type -> Stream.of(type.getDeclaredFields()))
                        .filter(SecretCoverageTest::isEncrypted)
                        .map(
                                field ->
                                        field.getDeclaringClass().getSimpleName()
                                                + "."
                                                + field.getName())
                        .filter(name -> !covered.contains(name))
                        .toList();

        assertThat(missing, empty());
    }

    private static boolean isEncrypted(Field field) {
        Convert convert = field.getAnnotation(Convert.class);
        return convert != null && convert.converter() == EncryptedStringConverter.class;
    }

    private static List<Class<?>> entities() {
        Path classes = Path.of("target", "classes");
        try (Stream<Path> files = Files.walk(classes)) {
            return files.filter(file -> file.toString().endsWith(".class"))
                    .map(file -> classes.relativize(file).toString())
                    .map(name -> name.replace(java.io.File.separatorChar, '.'))
                    .map(name -> name.substring(0, name.length() - ".class".length()))
                    .map(SecretCoverageTest::load)
                    .filter(java.util.Objects::nonNull)
                    .filter(type -> type.isAnnotationPresent(Entity.class))
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, SecretCoverageTest.class.getClassLoader());
        } catch (Throwable notLoadable) {
            return null;
        }
    }
}
