package io.keydra.security;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an operation whose use should be recorded.
 *
 * <p>An annotation rather than a call inside each method, so that adding an endpoint that changes
 * something and forgetting to audit it is visible in the same place the endpoint is declared,
 * instead of being invisible until someone goes looking for a record that was never written.
 */
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Audited {

    /**
     * The action name recorded, e.g. {@code key.delete}.
     *
     * <p>Non-binding, or CDI would match the interceptor only against the exact action name it was
     * declared with — which is to say, against nothing.
     */
    @Nonbinding
    String value();
}
