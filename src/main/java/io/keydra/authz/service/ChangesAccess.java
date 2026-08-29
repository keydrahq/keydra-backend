package io.keydra.authz.service;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a class whose transactions can change who may do what.
 *
 * <p>On the class rather than on each method, and the interceptor picks out the ones that write by
 * looking for {@code @WithTransaction}. That is the point: a service that grows another way to
 * change a grant is covered the day it is written, where a list of method names is a list somebody
 * eventually forgets to add to — and what that forgetting looks like is an account keeping access
 * for as long as an entry lives.
 */
@InterceptorBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface ChangesAccess {

    /** Present so the binding can be applied without a value; nothing reads it. */
    @Nonbinding
    boolean value() default true;
}
