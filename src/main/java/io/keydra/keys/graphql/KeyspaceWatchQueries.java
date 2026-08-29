package io.keydra.keys.graphql;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.keys.dto.KeyspaceWatchState;
import io.keydra.keys.service.KeyspaceWatch;
import io.keydra.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.DefaultValue;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

/**
 * The keyspace watch, on the surface the key browser already uses.
 *
 * <p>Transport, like the resource beside it: the same service, the same DTO, the same permissions.
 * The browser is on this surface for its key and namespace requests, and a lease it had to renew
 * over REST would be the one request in the page that went somewhere else.
 */
@GraphQLApi
public class KeyspaceWatchQueries {

    private final KeyspaceWatch watch;

    @Inject
    KeyspaceWatchQueries(KeyspaceWatch watch) {
        this.watch = watch;
    }

    @Query("keyspaceWatch")
    @Description("Whether a target announces its changes, and whether anybody is listening")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.KEYS_READ, connection = "connectionId")
    public Uni<KeyspaceWatchState> keyspaceWatch(
            @Name("connectionId") Long connectionId,
            @Name("database") @DefaultValue("0") @Description("Which database") int database) {
        return watch.state(connectionId, database);
    }

    @Mutation("holdKeyspaceWatch")
    @Description("Takes or renews a lease on a target's changes")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.KEYS_READ, connection = "connectionId")
    public Uni<KeyspaceWatchState> holdKeyspaceWatch(
            @Name("connectionId") Long connectionId,
            @Name("database") @DefaultValue("0") int database,
            @Name("lease") @Description("The lease to renew, or null to take one") String lease,
            @Name("keys")
                    @Description(
                            "Keys this lease is looking at, which are always reported when they"
                                + " change however full the sample was. Null or empty for a caller"
                                + " that only wants to know the list moved.")
                    java.util.List<String> keys) {
        return watch.hold(
                connectionId,
                database,
                lease,
                keys == null ? null : new java.util.LinkedHashSet<>(keys));
    }

    @Mutation("releaseKeyspaceWatch")
    @Description("Gives a lease back, closing the watch when it was the last")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.KEYS_READ, connection = "connectionId")
    public boolean releaseKeyspaceWatch(
            @Name("connectionId") Long connectionId,
            @Name("database") @DefaultValue("0") int database,
            @Name("lease") String lease) {
        return watch.release(connectionId, database, lease);
    }

    /**
     * Turning the target's announcements on.
     *
     * <p>The one operation here that is not reading: it changes a running server's configuration,
     * so it carries the permission the settings page carries rather than the one browsing carries.
     */
    @Mutation("announceKeyspaceChanges")
    @Description("Asks a target to announce its changes, keeping whatever its setting already said")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.SERVER_CONFIGURE, connection = "connectionId")
    public Uni<KeyspaceWatchState> announceKeyspaceChanges(
            @Name("connectionId") Long connectionId,
            @Name("database") @DefaultValue("0") int database) {
        return watch.announce(connectionId, database);
    }
}
