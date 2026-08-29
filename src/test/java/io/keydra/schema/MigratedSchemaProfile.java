package io.keydra.schema;

import io.quarkus.test.junit.QuarkusTestProfile;
import java.util.Map;

/**
 * Builds the schema with Flyway and then has Hibernate validate it.
 *
 * <p>This is the whole point of the test that uses it. Dev builds its schema from the entities and
 * production builds it from the migration; nothing otherwise checks that the two agree, and a
 * disagreement shows up as a production deployment that will not start — after the migration has
 * already run.
 */
public class MigratedSchemaProfile implements QuarkusTestProfile {

    @Override
    public Map<String, String> getConfigOverrides() {
        return Map.of(
                // JDBC exists only for Flyway; the application still speaks the reactive
                // driver. Its URL comes from PostgresResource, which publishes the
                // container's own.
                "quarkus.datasource.jdbc", "true",
                "quarkus.datasource.jdbc.max-size", "2",
                "quarkus.flyway.active", "true",
                "quarkus.flyway.migrate-at-start", "true",
                // Empty the schema first. The question is whether the migration builds the
                // right database from nothing, and "nothing" has to be arranged rather than
                // inherited: whatever ran before this left Hibernate's own tables behind, and
                // a migration that ran against those would be answering a different question.
                "quarkus.flyway.clean-at-start", "true",
                "quarkus.flyway.clean-disabled", "false",
                // Validate, not create: the question is whether the migration is right.
                "quarkus.hibernate-orm.schema-management.strategy", "validate");
    }
}
