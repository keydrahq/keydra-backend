package io.keydra.authz;

import io.keydra.authz.entity.Permission;
import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * What somebody must be able to do to call this.
 *
 * <p>One annotation naming a permission, rather than a list of roles. A role is a bundle somebody
 * configured; a permission is what the endpoint actually needs, and naming the second means a
 * deployment can rearrange the first without editing this application.
 *
 * <p>{@link #connection} names the method parameter carrying the target's id. An endpoint about
 * Keydra itself names nothing, and the permission it requires is one that can only be granted on
 * the instance — which the model checks rather than trusts.
 */
@InterceptorBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequiresPermission {

    /**
     * The permission the caller must hold.
     *
     * <p>Nonbinding, and this is not a detail. CDI matches an interceptor to a method by the
     * binding annotation <em>and its member values</em>, so without this the interceptor would only
     * intercept endpoints requiring the one permission its own annotation happens to name — and
     * every other endpoint would carry an annotation that did nothing at all. The value is read at
     * call time from the method, which is where it belongs.
     */
    @Nonbinding
    Permission value();

    /**
     * The parameter holding the connection id, for a permission about a target.
     *
     * <p>Empty for the instance permissions. Named rather than positional so that adding a
     * parameter to an endpoint cannot silently change which one is read.
     *
     * <p>Nonbinding for the same reason as {@link #value}.
     */
    @Nonbinding
    String connection() default "";
}
