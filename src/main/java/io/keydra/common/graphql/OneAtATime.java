package io.keydra.common.graphql;

import jakarta.enterprise.util.Nonbinding;
import jakarta.interceptor.InterceptorBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a GraphQL API whose resolvers must not run at the same time as each other.
 *
 * <p>GraphQL's whole point is asking for several things at once, and graphql-java honours that by
 * fetching the root fields of a query in parallel. Underneath, every one of those fetches ends in
 * the same Hibernate Reactive session — the request has one — and that session is strictly serial.
 * Two resolvers reading from it together produce "Session/EntityManager is closed" and "Illegal
 * pop() with non-matching JdbcValuesSourceProcessingState", neither of which names the cause, and
 * both of which arrive only when a page asks for a second field.
 *
 * <p>On the class rather than on each method, for the same reason {@code @ChangesAccess} is: a
 * query added next month is covered the day it is written. What it costs is the parallelism between
 * root fields of one request — which was never real here, since they queued behind one session
 * anyway — and what it keeps is the one round trip.
 */
@InterceptorBinding
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface OneAtATime {

    /** Present so the binding can be applied without a value; nothing reads it. */
    @Nonbinding
    boolean value() default true;
}
