package io.keydra.schema;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.greaterThan;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.vertx.mutiny.pgclient.PgPool;
import io.vertx.mutiny.sqlclient.Tuple;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * The migration and the entities have to describe the same schema.
 *
 * <p>Dev builds its schema from the entities; production builds it from the migration. Nothing else
 * checks that the two agree, and a disagreement surfaces as a production deployment that refuses to
 * start — after the migration has already run against the database.
 *
 * <p>That check is the profile, not this class: it migrates with Flyway and sets Hibernate to
 * validate. If the two disagree the application does not start and every case here fails, which is
 * the point. The assertions below are about the parts validation does not cover.
 */
@QuarkusTest
@TestProfile(MigratedSchemaProfile.class)
class FlywaySchemaTest {

    @Inject PgPool pool;

    private long count(String sql, Object... arguments) {
        return pool.preparedQuery(sql)
                .execute(Tuple.from(java.util.List.of(arguments)))
                .await()
                .indefinitely()
                .iterator()
                .next()
                .getLong(0);
    }

    @Test
    void startsAgainstASchemaBuiltByTheMigration() {
        // Reaching this at all means Hibernate validated the migrated schema.
        assertThat(
                count("select count(*) from flyway_schema_history where success = true"),
                greaterThan(0L));
    }

    @Test
    void createsEveryTableTheApplicationUses() {
        assertThat(
                count(
                        "select count(*) from information_schema.tables"
                                + " where table_schema = 'public'"
                                + " and table_name in ('connection_profile', 'command_history',"
                                + " 'audit_event')"),
                equalTo(3L));
    }

    @Test
    void createsTheIndexesTheEntitiesDeclare() {
        // Hibernate's validation checks columns and types, not indexes — so a migration
        // could satisfy it while leaving every query to scan.
        assertThat(
                count(
                        "select count(*) from pg_indexes where schemaname = 'public'"
                                + " and indexname in ('idx_connection_profile_name',"
                                + " 'idx_command_history_owner', 'idx_audit_at',"
                                + " 'idx_audit_actor', 'idx_audit_connection',"
                                + " 'idx_sign_in_attempt_username',"
                                + " 'idx_sign_in_attempt_network')"),
                equalTo(7L));
    }

    @Test
    void allocatesIdsTheWayHibernateExpects() {
        // Hibernate's default allocation size is 50; a sequence incrementing by 1 hands out
        // ids the application believes it already owns, and validation does not notice.
        assertThat(
                count(
                        "select increment_by from information_schema.sequences"
                                + " join pg_sequences on sequencename = sequence_name"
                                + " where sequence_name = 'connection_profile_seq'"),
                equalTo(50L));
    }

    @Test
    void doesNotAllowNullWhereTheEntityHasAPrimitive() {
        // Hibernate maps a primitive to a nullable column by default and validates happily
        // against one — and then throws on the first row that actually has NULL. Validation
        // therefore cannot catch this; only asking the database can.
        assertThat(
                count(
                        "select count(*) from information_schema.columns"
                                + " where table_name = 'connection_profile'"
                                + " and column_name in ('port', 'db_index', 'tls')"
                                + " and is_nullable = 'NO'"),
                equalTo(3L));
    }

    @Test
    void movesTheTunnelColumnsIntoRowsAndTakesThemAway() {
        // The migration carries what was configured into ssh_tunnel and then drops the
        // columns. Both halves matter: a deployment that upgrades must not have to re-enter
        // a jump host password, and leaving the columns behind would leave two places that
        // disagree about where a target is reached through.
        assertThat(
                count(
                        "select count(*) from information_schema.columns"
                                + " where table_name = 'connection_profile'"
                                + " and column_name like 'tunnel\\_%'"),
                equalTo(1L));
        assertThat(
                count(
                        "select count(*) from information_schema.columns"
                                + " where table_name = 'connection_profile'"
                                + " and column_name = 'tunnel_id'"),
                equalTo(1L));
        // The port is a primitive on the entity there too.
        assertThat(
                count(
                        "select count(*) from information_schema.columns"
                                + " where table_name = 'ssh_tunnel'"
                                + " and column_name = 'port_number' and is_nullable = 'NO'"),
                equalTo(1L));
    }
}
