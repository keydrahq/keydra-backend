package io.keydra;

import io.keydra.resources.PostgresResource;
import io.quarkus.test.common.TestResourceScope;
import io.quarkus.test.common.WithTestResource;
import io.restassured.RestAssured;
import org.junit.jupiter.api.BeforeAll;

/**
 * Shared setup for HTTP tests.
 *
 * <p>Also the single place the PostgreSQL container is declared. The scope is {@code GLOBAL}
 * because Hibernate Reactive has no in-process driver, so the application cannot boot without a
 * database — even tests that never touch persistence need one. Declaring it once here keeps that
 * fact from being repeated on every test class.
 *
 * <p>Concrete tests add {@code @QuarkusTest} and, where they exercise a single resource,
 * {@code @TestHTTPEndpoint(TheResource.class)} so no URL strings are hardcoded.
 */
@WithTestResource(value = PostgresResource.class, scope = TestResourceScope.GLOBAL)
public abstract class AbstractTestBase {

    @BeforeAll
    static void configureRestAssured() {
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }
}
