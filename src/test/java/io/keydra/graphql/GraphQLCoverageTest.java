package io.keydra.graphql;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import io.quarkus.security.Authenticated;
import io.smallrye.graphql.api.Subscription;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Query;
import org.junit.jupiter.api.Test;

/**
 * Every GraphQL operation says who may run it.
 *
 * <p>The same test the REST endpoints have, for the same reason and against the same failure. A
 * permission model is only as good as its weakest entry point, and the way it fails is never a
 * wrong annotation — it is a new one added without any, which no reviewer notices because there is
 * nothing on the screen to notice. A second API surface doubles the number of places that can
 * happen.
 *
 * <p>Walks the compiled classes rather than a list somebody maintains, so adding a resolver cannot
 * also mean forgetting to add it here.
 */
class GraphQLCoverageTest {

    /** What makes a method an operation somebody can call. */
    private static final List<Class<? extends Annotation>> OPERATIONS =
            List.of(Query.class, Mutation.class, Subscription.class);

    /**
     * Operations that are deliberately open, and why.
     *
     * <p>Empty, and it should take an argument to make it otherwise. Everything Keydra's schema
     * exposes is about somebody's servers; there is no equivalent here of "what build is this",
     * which is the kind of question the REST surface leaves open.
     */
    private static final List<String> OPEN = List.of();

    @Test
    void everyOperationNamesWhoMayRunIt() {
        List<String> unguarded =
                resolvers().stream()
                        .flatMap(type -> Stream.of(type.getDeclaredMethods()))
                        .filter(GraphQLCoverageTest::isOperation)
                        .filter(method -> !isGuarded(method))
                        .map(GraphQLCoverageTest::name)
                        .filter(name -> !OPEN.contains(name))
                        .toList();

        assertThat(unguarded, is(empty()));
    }

    @Test
    void thereIsSomethingToCheck() {
        // A walk that found nothing would pass the test above while proving nothing, which is
        // how a coverage test quietly stops covering anything.
        List<Method> operations =
                resolvers().stream()
                        .flatMap(type -> Stream.of(type.getDeclaredMethods()))
                        .filter(GraphQLCoverageTest::isOperation)
                        .toList();

        assertThat(operations.isEmpty(), is(false));
    }

    private static boolean isOperation(Method method) {
        return OPERATIONS.stream().anyMatch(method::isAnnotationPresent);
    }

    /**
     * Whether a caller is checked before the method runs.
     *
     * <p>The role annotation is the coarse gate and is what this insists on: it decides whether the
     * caller is anybody at all. Which of them may do this particular thing is decided further in,
     * by the grants in the database — and that check cannot be seen from here, because for the
     * lists it is a filter inside the service rather than an annotation.
     */
    /**
     * What counts as saying who may run something.
     *
     * <p>{@code @Authenticated} counts, and it is not a weaker answer than a role — it is a
     * different one. Managing your own sessions, and asking what you may do, require being signed
     * in and nothing else: an account with no grants can still be signed in on a laptop somebody
     * left somewhere, and the page that lets them end that session must not need a role to reach.
     * What this test is against is an operation with no guard at all, which is the one that ships
     * unnoticed.
     */
    private static boolean isGuarded(Method method) {
        return method.isAnnotationPresent(RolesAllowed.class)
                || method.getDeclaringClass().isAnnotationPresent(RolesAllowed.class)
                || method.isAnnotationPresent(Authenticated.class)
                || method.getDeclaringClass().isAnnotationPresent(Authenticated.class)
                || method.isAnnotationPresent(PermitAll.class);
    }

    private static String name(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName();
    }

    private static List<Class<?>> resolvers() {
        Path classes = Path.of("target", "classes");
        try (Stream<Path> files = Files.walk(classes)) {
            List<Class<?>> found = new ArrayList<>();
            files.filter(file -> file.toString().endsWith(".class"))
                    .map(file -> classes.relativize(file).toString())
                    .map(name -> name.replace(java.io.File.separatorChar, '.'))
                    .map(name -> name.substring(0, name.length() - ".class".length()))
                    .map(GraphQLCoverageTest::load)
                    .filter(java.util.Objects::nonNull)
                    .filter(type -> type.isAnnotationPresent(GraphQLApi.class))
                    .forEach(found::add);
            return found;
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, GraphQLCoverageTest.class.getClassLoader());
        } catch (Throwable notLoadable) {
            return null;
        }
    }
}
