package io.keydra.connections.graphql;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.common.graphql.OneAtATime;
import io.keydra.connections.dto.ConnectionRequest;
import io.keydra.connections.dto.ConnectionResponse;
import io.keydra.connections.dto.ConnectionStatus;
import io.keydra.connections.service.ConnectionService;
import io.keydra.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import java.util.List;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

/**
 * The targets, for the pages that need to name one.
 *
 * <p>Here because almost every list in Keydra is about connections without being a list of them: a
 * migration names two of them by id, a schedule one, an alert one. Those pages then made a second
 * request for the catalogue so they could show a name instead of a number — which is the round trip
 * a single query exists to remove.
 *
 * <p>Filtered to what the caller can see, by the same service the resource uses. A page that
 * resolved a name Keydra would not have shown it in a list would be a leak dressed as a label.
 */
@GraphQLApi
@OneAtATime
public class ConnectionQueries {

    private final ConnectionService service;

    @Inject
    ConnectionQueries(ConnectionService service) {
        this.service = service;
    }

    @Query("connections")
    @Description("Every target the caller can see, with its last known status")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    public Uni<List<ConnectionResponse>> connections() {
        return service.list();
    }

    @Query("connection")
    @Description("One target, by id")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.CONNECTION_VIEW, connection = "id")
    public Uni<ConnectionResponse> connection(@Name("id") Long id) {
        return service.get(id);
    }

    // --- Changing them ------------------------------------------------------

    @Mutation("createConnection")
    @Description("Adds a target")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.CONNECTION_CREATE)
    public Uni<ConnectionResponse> createConnection(
            @Name("connection") @Valid ConnectionRequest connection) {
        return service.create(connection);
    }

    @Mutation("updateConnection")
    @Description("Changes a target")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(value = Permission.CONNECTION_EDIT, connection = "id")
    public Uni<ConnectionResponse> updateConnection(
            @Name("id") Long id, @Name("connection") @Valid ConnectionRequest connection) {
        return service.update(id, connection);
    }

    /**
     * Removes a target.
     *
     * <p>Answers true rather than the profile that is gone. There is nothing useful to return: the
     * caller asked for it not to exist, and it does not.
     */
    @Mutation("deleteConnection")
    @Description("Removes a target and everything Keydra kept about it")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(value = Permission.CONNECTION_DELETE, connection = "id")
    public Uni<Boolean> deleteConnection(@Name("id") Long id) {
        return service.delete(id).replaceWith(true);
    }

    /**
     * Opens a connection to the target and reports what came back.
     *
     * <p>A mutation rather than a query, and not because it writes: it dials out. A query is
     * expected to be safe to run twice and safe to cache, and a TCP connection to somebody's server
     * is neither — nor is the tunnel it may have to open to get there.
     *
     * <p>Takes the whole profile so one can be tried before it is saved, with an id alongside for
     * taking an unchanged password from a saved one. Typing a password again to test a port change
     * is how people end up not testing.
     *
     * <p>The profile may be left out entirely, which means "the saved one, exactly as it is" — and
     * that one does record what it found, because it is the target's own status being checked
     * rather than a form's guess at one.
     */
    @Mutation("checkConnection")
    @Description("Opens a connection to the target and reports what came back")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(Permission.CONNECTION_CREATE)
    public Uni<ConnectionStatus> checkConnection(
            @Name("id") @Description("A saved target to take an unchanged password from") Long id,
            @Name("connection")
                    @Description("What to try, or nothing for the saved one as it is")
                    @Valid
                    ConnectionRequest connection) {
        return connection == null ? service.test(id) : service.testDraft(id, connection);
    }
}
