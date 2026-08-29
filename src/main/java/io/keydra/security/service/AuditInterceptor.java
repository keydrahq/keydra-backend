package io.keydra.security.service;

import io.keydra.security.Audited;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InvocationContext;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.QueryParam;
import java.lang.annotation.Annotation;
import java.lang.reflect.Parameter;

/**
 * Records the use of anything marked {@link Audited}.
 *
 * <p>Wraps the returned {@code Uni} rather than recording before it runs, so the log says what
 * happened and not what was attempted — a delete that failed is recorded as a failed delete.
 *
 * <p>The connection id is taken from a {@code connectionId} parameter when the method has one, in
 * the path or in the query string — both, because an endpoint about a target names it wherever its
 * own shape puts it, and a log that only understood one of the two left half its rows saying the
 * change was about nothing. That is the only argument this reads: recording arguments generally
 * would put values, and therefore secrets, into the log.
 *
 * <p>A refusal records what was said about it. Nothing else does: the message is the one the caller
 * already received, so it discloses nothing new, and a row that says only "refused" sends whoever
 * reads it looking through server logs for the sentence that was right there.
 */
@Audited("")
@Interceptor
@Priority(Interceptor.Priority.APPLICATION)
public class AuditInterceptor {

    /** The name an endpoint gives the target it is about, wherever it takes it. */
    private static final String CONNECTION_ID = "connectionId";

    /** As wide as the column, so a long refusal is trimmed here rather than by the database. */
    private static final int MAX_DETAIL = 1024;

    private final AuditService audit;

    @Inject
    AuditInterceptor(AuditService audit) {
        this.audit = audit;
    }

    @AroundInvoke
    Object record(InvocationContext context) throws Exception {
        Audited annotation = annotation(context);
        if (annotation == null) {
            return context.proceed();
        }

        String action = annotation.value();
        Long connectionId = connectionId(context);
        Object result = context.proceed();

        if (result instanceof Uni<?> uni) {
            return uni.onItemOrFailure()
                    .call(
                            (item, failure) ->
                                    audit.record(
                                            action,
                                            connectionId,
                                            whyRefused(failure),
                                            failure == null));
        }

        // A synchronous method has already succeeded by the time this runs.
        audit.record(action, connectionId, null, true)
                .subscribe()
                .with(ignored -> {}, ignored -> {});
        return result;
    }

    private static Audited annotation(InvocationContext context) {
        Audited method = context.getMethod().getAnnotation(Audited.class);
        return method != null
                ? method
                : context.getMethod().getDeclaringClass().getAnnotation(Audited.class);
    }

    private static Long connectionId(InvocationContext context) {
        Parameter[] parameters = context.getMethod().getParameters();
        Object[] arguments = context.getParameters();
        for (int i = 0; i < parameters.length && i < arguments.length; i++) {
            if (namesTheConnection(parameters[i]) && arguments[i] instanceof Long id) {
                return id;
            }
        }
        return null;
    }

    /** Whether a parameter is the target this call is about, however the endpoint receives it. */
    private static boolean namesTheConnection(Parameter parameter) {
        for (Annotation annotation : parameter.getAnnotations()) {
            if (annotation instanceof PathParam path && CONNECTION_ID.equals(path.value())) {
                return true;
            }
            if (annotation instanceof QueryParam query && CONNECTION_ID.equals(query.value())) {
                return true;
            }
        }
        return false;
    }

    /**
     * What was said about a refusal, or nothing when there was none.
     *
     * <p>The message rather than the exception: whoever reads an audit log needs the sentence the
     * caller was given, not a class name and a stack trace that lives in the server log anyway.
     */
    private static String whyRefused(Throwable failure) {
        if (failure == null) {
            return null;
        }
        String message = failure.getMessage();
        String text =
                message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
        return text.length() > MAX_DETAIL ? text.substring(0, MAX_DETAIL) : text;
    }
}
