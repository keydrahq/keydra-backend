package io.keydra.authz;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import io.keydra.authz.entity.Permission;
import io.quarkus.security.Authenticated;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/**
 * Every endpoint says what it requires.
 *
 * <p>This is the test the whole phase exists for. A permission model is only as good as its weakest
 * endpoint, and the failure mode is not a wrong annotation — it is a new endpoint somebody added
 * without one, which no reviewer notices because there is nothing on the screen to notice.
 *
 * <p>Walks the compiled resource classes rather than a list somebody maintains, so adding a
 * resource cannot also mean forgetting to add it here.
 */
class EndpointCoverageTest {

    /** The HTTP verbs that make a method an endpoint. */
    private static final List<Class<? extends Annotation>> VERBS =
            List.of(GET.class, POST.class, PUT.class, DELETE.class);

    /**
     * Endpoints that are deliberately open, and why.
     *
     * <p>Each of these answers a question somebody has to be able to ask before they are anybody:
     * what this build is, whether security is even on, who Keydra thinks they are, and — on an
     * instance with no accounts at all — how to create the first administrator. An endpoint that
     * required a permission to answer "you have no permissions" would be a locked door with the key
     * inside.
     */
    private static final List<String> OPEN =
            List.of(
                    "io.keydra.about.rest.About",
                    "io.keydra.authz.rest.Authentication",
                    "io.keydra.authz.rest.SignIn",
                    "io.keydra.security.rest.Security#me",
                    "io.keydra.console.rest.Console#deniedCommands",
                    "io.keydra.values.rest.Values#encodings",
                    "io.keydra.pubsub.rest.Subscriptions#list",
                    "io.keydra.keys.rest.Migrations#list",
                    "io.keydra.keys.rest.Databases#list",
                    "io.keydra.connections.rest.Connections#list",
                    // The same case as the catalog: a schedule is about a target, so the list
                    // and the history show only the schedules of targets the caller can see.
                    // Visibility is the filter, and a permission here would be a second answer
                    // to a question already answered.
                    "io.keydra.schedule.rest.Schedules#list",
                    "io.keydra.schedule.rest.Schedules#runs",
                    // A closed list of what can be scheduled: it says nothing about this
                    // instance and everything about this build.
                    "io.keydra.schedule.rest.Schedules#jobTypes",
                    // The same case again: a rule is about a target, so the rules and their
                    // history show only the ones whose target the caller can see, and the
                    // metrics are a property of this build rather than of this instance.
                    "io.keydra.alerts.rest.Alerts#list",
                    "io.keydra.alerts.rest.Alerts#events",
                    "io.keydra.alerts.rest.Alerts#metrics",
                    // The half of the invitation flow that anybody holding a link uses. It is
                    // open by necessity: whoever follows the link has no account to
                    // authenticate with yet, which is the entire reason the link exists. What
                    // protects it is the link itself — 256 random bits, stored only as a hash,
                    // expiring, and good for one use.
                    "io.keydra.authz.rest.Invitations#standing",
                    "io.keydra.authz.rest.Invitations#accept",
                    // Open for the same reason and answering the same way whether or not there
                    // is such an account, because anything else is a way to ask Keydra who has
                    // one here.
                    "io.keydra.authz.rest.Invitations#forgotten",
                    // Your own preferences, and the only ones any of these can reach — there is
                    // no path here that names an account. Open rather than authenticated because
                    // an instance with enforcement off has nobody to keep preferences for and is
                    // still an instance somebody uses: asking anonymously answers "nothing, and
                    // nowhere to put it", and the browser keeps its own copy as it always did.
                    // Requiring a permission would make an open instance one with no theme
                    // switch.
                    "io.keydra.preferences.rest.Preferences",
                    // Which permission answering a request needs depends on what the request says
                    // — keys:delete for a purge, transfer:import for an import, migration:run on
                    // both ends for a migration — so no fixed annotation could be right for more
                    // than one row of the table. An annotation naming a permission the caller
                    // supplied is worse than none, which is phase 59's finding and the reason this
                    // decision is made in the service, against the row. Reading the list is the
                    // schedules' case exactly: a request is about a target, and the list shows only
                    // targets the caller can see.
                    "io.keydra.approvals.rest.Approvals");

    @Test
    void everyEndpointNamesThePermissionItRequires() {
        List<String> unprotected = new ArrayList<>();

        for (Class<?> resource : resources()) {
            for (Method method : resource.getDeclaredMethods()) {
                if (!isEndpoint(method) || isOpen(resource, method)) {
                    continue;
                }
                if (isSignedInEnough(method)) {
                    continue;
                }
                if (required(method) == null) {
                    unprotected.add(resource.getName() + "#" + method.getName());
                }
            }
        }

        // A new endpoint with no annotation is the failure this test exists to catch. If one
        // is deliberately open, it goes in OPEN above with a reason — which is a decision
        // somebody made rather than one nobody noticed.
        assertThat(unprotected, is(empty()));
    }

    @Test
    void everyPermissionAboutATargetNamesWhereToFindIt() {
        List<String> wrong = new ArrayList<>();

        for (Class<?> resource : resources()) {
            for (Method method : resource.getDeclaredMethods()) {
                RequiresPermission required = required(method);
                if (required == null) {
                    continue;
                }
                boolean aboutATarget = required.value().level() == Permission.Level.CONNECTION;
                boolean namesOne = !required.connection().isEmpty();

                if (aboutATarget != namesOne) {
                    wrong.add(resource.getName() + "#" + method.getName());
                    continue;
                }
                if (namesOne && !hasParameter(method, required.connection())) {
                    // Named rather than positional, so this is checkable: an annotation
                    // naming a parameter that is not there would fail at call time, on the
                    // request it was supposed to protect.
                    wrong.add(resource.getName() + "#" + method.getName() + " (no such parameter)");
                }
            }
        }

        assertThat(wrong, is(empty()));
    }

    /**
     * Every guarded endpoint answers with a {@code Uni} or a {@code Multi}.
     *
     * <p>Not a style rule. The permission check is itself asynchronous, so the interceptor has to
     * fold a refusal into the returned reactive type; a method that answers with a plain value
     * gives it nowhere to put one, and it fails at call time — on the request it was supposed to
     * protect, as a 500 rather than a 403.
     *
     * <p>This is written down because it already happened: six endpoints returned plain values and
     * nobody noticed until the interceptor started matching at all.
     */
    @Test
    void everyGuardedEndpointAnswersReactively() {
        List<String> wrong = new ArrayList<>();

        for (Class<?> resource : resources()) {
            for (Method method : resource.getDeclaredMethods()) {
                if (!isEndpoint(method) || required(method) == null) {
                    continue;
                }
                Class<?> answer = method.getReturnType();
                if (!io.smallrye.mutiny.Uni.class.isAssignableFrom(answer)
                        && !io.smallrye.mutiny.Multi.class.isAssignableFrom(answer)) {
                    wrong.add(resource.getName() + "#" + method.getName() + " returns " + answer);
                }
            }
        }

        assertThat(wrong, is(empty()));
    }

    private static boolean hasParameter(Method method, String name) {
        return Arrays.stream(method.getParameters())
                .anyMatch(parameter -> parameter.getName().equals(name) && isBound(parameter));
    }

    /** A parameter the framework fills from the request, rather than one of the body. */
    private static boolean isBound(Parameter parameter) {
        return parameter.isAnnotationPresent(PathParam.class)
                || parameter.isAnnotationPresent(QueryParam.class);
    }

    /**
     * Whether being signed in is the whole requirement.
     *
     * <p>True for one thing only, and it is a decision rather than an oversight: managing your own
     * sessions. An account with no grants at all can still be signed in on a laptop somebody left
     * somewhere, and requiring a role to end that session would mean the people most likely to need
     * it are the ones who cannot. Everything else here answers a question about somebody's servers
     * and asks for a permission to answer it.
     */
    private static boolean isSignedInEnough(Method method) {
        return method.getDeclaringClass().isAnnotationPresent(Authenticated.class)
                || method.isAnnotationPresent(Authenticated.class);
    }

    private static RequiresPermission required(Method method) {
        RequiresPermission onMethod = method.getAnnotation(RequiresPermission.class);
        return onMethod != null
                ? onMethod
                : method.getDeclaringClass().getAnnotation(RequiresPermission.class);
    }

    private static boolean isEndpoint(Method method) {
        return VERBS.stream().anyMatch(method::isAnnotationPresent);
    }

    private static boolean isOpen(Class<?> resource, Method method) {
        return OPEN.contains(resource.getName())
                || OPEN.contains(resource.getName() + "#" + method.getName());
    }

    /**
     * Every class carrying a JAX-RS path, found by walking what was compiled.
     *
     * <p>The class files rather than a scanner: this test runs without starting the application, so
     * it is fast enough to be the thing that fails first when somebody adds an endpoint.
     */
    private static List<Class<?>> resources() {
        java.nio.file.Path classes = java.nio.file.Path.of("target", "classes");
        try (Stream<java.nio.file.Path> files = Files.walk(classes)) {
            return files.filter(file -> file.toString().endsWith(".class"))
                    .map(file -> classes.relativize(file).toString())
                    .map(name -> name.replace(java.io.File.separatorChar, '.'))
                    .map(name -> name.substring(0, name.length() - ".class".length()))
                    .map(EndpointCoverageTest::load)
                    .filter(java.util.Objects::nonNull)
                    .filter(type -> type.isAnnotationPresent(Path.class))
                    .toList();
        } catch (IOException unreadable) {
            throw new UncheckedIOException(unreadable);
        }
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name, false, EndpointCoverageTest.class.getClassLoader());
        } catch (Throwable notLoadable) {
            // A class that cannot be loaded outside the application context is not a
            // resource this test can check, and is not one it should fail for.
            return null;
        }
    }
}
