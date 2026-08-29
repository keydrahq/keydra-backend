package io.keydra.authz.entity;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;

import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * What the three roles carry, and that each contains the one below it.
 *
 * <p>The hierarchy is the thing worth pinning: it is stated nowhere in the code except by the
 * OPERATOR definition including VIEWER's set, and somebody adding a permission to VIEWER without
 * that inclusion would break it silently — an operator who cannot do something a viewer can is a
 * bug nobody reports because nobody believes it.
 */
class BuiltInRoleTest {

    @Test
    void anOperatorCanDoEverythingAViewerCan() {
        assertThat(
                BuiltInRole.VIEWER.permissions(),
                everyItem(is(in(BuiltInRole.OPERATOR.permissions()))));
    }

    @Test
    void anAdministratorCanDoEverythingAnOperatorCan() {
        assertThat(
                BuiltInRole.OPERATOR.permissions(),
                everyItem(is(in(BuiltInRole.ADMIN.permissions()))));
    }

    @Test
    void aViewerChangesNothing() {
        // The read-only role is the one whose contents are worth listing rather than
        // describing: every write permission below is one somebody could add by accident.
        List<Permission> writes =
                List.of(
                        Permission.KEYS_WRITE,
                        Permission.KEYS_DELETE,
                        Permission.VALUES_WRITE,
                        Permission.CONSOLE_RUN,
                        Permission.PUBSUB_PUBLISH,
                        Permission.SERVER_CONFIGURE,
                        Permission.ACL_MANAGE,
                        Permission.MIGRATION_RUN,
                        Permission.TRANSFER_IMPORT,
                        Permission.CONNECTION_EDIT,
                        Permission.CONNECTION_DELETE);

        writes.forEach(write -> assertThat(BuiltInRole.VIEWER.permissions(), not(hasItem(write))));
    }

    @Test
    void aViewerMayNotWatchEveryCommandTheServerRuns() {
        // The most revealing thing Keydra can show: every command every client sends,
        // including the ones carrying data. Reading one key at a time is a different act.
        assertThat(BuiltInRole.VIEWER.permissions(), not(hasItem(Permission.COMMANDS_WATCH)));
    }

    @Test
    void onlyAnAdministratorHoldsThePermissionsAboutKeydraItself() {
        Arrays.stream(Permission.values())
                .filter(permission -> permission.level() == Permission.Level.INSTANCE)
                .forEach(
                        instance -> {
                            assertThat(BuiltInRole.VIEWER.permissions(), not(hasItem(instance)));
                            assertThat(BuiltInRole.OPERATOR.permissions(), not(hasItem(instance)));
                            assertThat(BuiltInRole.ADMIN.permissions(), hasItem(instance));
                        });
    }

    @Test
    void everyPermissionIsNamedOnceAndReadableBack() {
        // The name is what a grant stores and what an endpoint names, so a duplicate would
        // make two permissions one.
        List<String> ids = Arrays.stream(Permission.values()).map(Permission::id).toList();

        assertThat(ids, hasSize(Permission.values().length));
        assertThat(ids.stream().distinct().toList(), hasSize(ids.size()));
        Arrays.stream(Permission.values())
                .forEach(
                        permission ->
                                assertThat(
                                        Permission.byId(permission.id()).orElseThrow(),
                                        is(permission)));
    }

    @Test
    void aTokenClaimNamesTheRoleItMeans() {
        // Existing deployments carry these three words in a claim, and they have to keep
        // meaning what they meant.
        assertThat(BuiltInRole.byId("viewer").orElseThrow(), is(BuiltInRole.VIEWER));
        assertThat(BuiltInRole.byId("ADMIN").orElseThrow(), is(BuiltInRole.ADMIN));
        assertThat(BuiltInRole.byId("nobody").isPresent(), is(false));
    }

    private static <T> org.hamcrest.Matcher<T> in(java.util.Collection<T> collection) {
        return org.hamcrest.Matchers.in(collection);
    }
}
