package io.keydra.authz.service;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.exception.PermissionDeniedException;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;

/**
 * Refuses a call before it runs, when the caller does not hold what it requires.
 *
 * <p>Before rather than after: an endpoint that deletes a keyspace and then discovers the caller
 * was not allowed to has already deleted it. So the check is a gate, and the method body is never
 * entered — which is also why this cannot be a filter that inspects the response.
 *
 * <p>The result is a {@code Uni} or a {@code Multi} on every endpoint here, so the refusal is
 * folded into that rather than thrown: throwing from an interceptor around a reactive method
 * produces a failure the framework reports as a server error rather than as the refusal it is.
 */
@Interceptor
@RequiresPermission(value = io.keydra.authz.entity.Permission.CONNECTION_VIEW)
@Priority(Interceptor.Priority.APPLICATION)
public class PermissionInterceptor {

    private final CallerPermissions caller;

    @Inject
    PermissionInterceptor(CallerPermissions caller) {
        this.caller = caller;
    }

    @AroundInvoke
    public Object check(InvocationContext context) throws Exception {
        RequiresPermission required = required(context.getMethod());
        if (required == null) {
            return context.proceed();
        }

        Long connectionId = connectionId(context, required.connection());

        Uni<Boolean> allowed = caller.holds(required.value(), connectionId);

        // The two reactive shapes this application returns. Anything else is a mistake worth
        // failing loudly for rather than letting through unchecked.
        if (Uni.class.isAssignableFrom(context.getMethod().getReturnType())) {
            return allowed.flatMap(
                    permitted -> {
                        if (!permitted) {
                            return Uni.createFrom()
                                    .failure(
                                            new PermissionDeniedException(
                                                    required.value(), connectionId));
                        }
                        return proceedAsUni(context);
                    });
        }
        if (Multi.class.isAssignableFrom(context.getMethod().getReturnType())) {
            return allowed.onItem()
                    .transformToMulti(
                            permitted -> {
                                if (!permitted) {
                                    return Multi.createFrom()
                                            .failure(
                                                    new PermissionDeniedException(
                                                            required.value(), connectionId));
                                }
                                return proceedAsMulti(context);
                            });
        }
        throw new IllegalStateException(
                "@RequiresPermission is for reactive endpoints; "
                        + context.getMethod()
                        + " returns "
                        + context.getMethod().getReturnType());
    }

    @SuppressWarnings("unchecked")
    private static Uni<Object> proceedAsUni(InvocationContext context) {
        try {
            return (Uni<Object>) context.proceed();
        } catch (Exception failure) {
            return Uni.createFrom().failure(failure);
        }
    }

    @SuppressWarnings("unchecked")
    private static Multi<Object> proceedAsMulti(InvocationContext context) {
        try {
            return (Multi<Object>) context.proceed();
        } catch (Exception failure) {
            return Multi.createFrom().failure(failure);
        }
    }

    /** The annotation on the method, or on the class when the whole resource carries one. */
    private static RequiresPermission required(Method method) {
        RequiresPermission onMethod = method.getAnnotation(RequiresPermission.class);
        return onMethod != null
                ? onMethod
                : method.getDeclaringClass().getAnnotation(RequiresPermission.class);
    }

    /**
     * The connection this call is about, read from the parameter the annotation names.
     *
     * <p>By name rather than by position, so adding a parameter to an endpoint cannot silently
     * change which one is read — which would be a permission check against the wrong target.
     */
    private static Long connectionId(InvocationContext context, String parameterName) {
        if (parameterName.isEmpty()) {
            return null;
        }
        Parameter[] parameters = context.getMethod().getParameters();
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getName().equals(parameterName)) {
                Object value = context.getParameters()[i];
                if (value == null) {
                    // Not a fault. A parameter that can be absent means the caller named no
                    // target, which is a question the resolver already answers — it resolves the
                    // instance-level grants and nothing else. Reading it as a number produced
                    // Long.valueOf("null") and a 500 on an endpoint that was working correctly.
                    return null;
                }
                return value instanceof Long id ? id : Long.valueOf(String.valueOf(value));
            }
        }
        throw new IllegalStateException(
                "@RequiresPermission names a parameter this method does not have: "
                        + parameterName
                        + " on "
                        + context.getMethod());
    }
}
