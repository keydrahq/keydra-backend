package io.keydra.tunnels.graphql;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.common.graphql.OneAtATime;
import io.keydra.security.Roles;
import io.keydra.tunnels.dto.TunnelDtos.TunnelCheck;
import io.keydra.tunnels.dto.TunnelDtos.TunnelRequest;
import io.keydra.tunnels.dto.TunnelDtos.TunnelSummary;
import io.keydra.tunnels.service.TunnelService;
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
 * The jump hosts Keydra reaches targets through.
 *
 * <p>Transport only, calling the same service the resource calls — which is what keeps the two
 * surfaces from disagreeing about what a tunnel is or who may see one.
 *
 * <p>Administrator and the tunnel permission on every operation, reading included. A tunnel holds a
 * private key and a passphrase; the summary never returns them, but the list of jump hosts an
 * estate reaches through is itself a map of that estate, and it is not for everyone who can browse
 * a key.
 *
 * <p>A plain list rather than a connection: jump hosts are typed in by hand and number in the tens.
 * Cursors are for lists that grow on their own.
 */
@GraphQLApi
@OneAtATime
@RolesAllowed(Roles.ADMIN)
@RequiresPermission(Permission.TUNNEL_MANAGE)
public class TunnelQueries {

    private final TunnelService service;

    @Inject
    TunnelQueries(TunnelService service) {
        this.service = service;
    }

    @Query("tunnels")
    @Description("Every jump host, without the keys they hold")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.TUNNEL_MANAGE)
    public Uni<List<TunnelSummary>> tunnels() {
        return service.list();
    }

    @Mutation("createTunnel")
    @Description("Adds a jump host")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.TUNNEL_MANAGE)
    public Uni<TunnelSummary> createTunnel(@Name("tunnel") @Valid TunnelRequest tunnel) {
        return service.create(tunnel);
    }

    @Mutation("updateTunnel")
    @Description("Changes a jump host")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.TUNNEL_MANAGE)
    public Uni<TunnelSummary> updateTunnel(
            @Name("id") Long id, @Name("tunnel") @Valid TunnelRequest tunnel) {
        return service.update(id, tunnel);
    }

    /** Answers whether there was one to remove; deleting something twice is not an error. */
    @Mutation("deleteTunnel")
    @Description("Removes a jump host")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.TUNNEL_MANAGE)
    public Uni<Boolean> deleteTunnel(@Name("id") Long id) {
        return service.delete(id);
    }

    /**
     * Opens the tunnel and reports what happened.
     *
     * <p>A mutation rather than a query, and not because it writes: it dials out. A query is
     * expected to be safe to run twice and safe to cache, and an SSH connection to somebody's jump
     * host is neither.
     *
     * <p>Takes the whole tunnel rather than only its id, so a jump host can be tried before it is
     * saved — which is the difference between finding out now and finding out when a backup fails
     * at three in the morning. An id alongside it means "this saved one, with these changes", so an
     * unchanged passphrase does not have to be typed again to test it.
     *
     * <p>The tunnel may be left out entirely, which means "the saved one, exactly as it is". That
     * is what a list of jump hosts with a test button beside each row is asking, and making it send
     * back a description of what it is already looking at would be asking it to invent one.
     */
    @Mutation("checkTunnel")
    @Description("Opens the tunnel and reports what happened")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.TUNNEL_MANAGE)
    public Uni<TunnelCheck> checkTunnel(
            @Name("id") @Description("A saved jump host to take unchanged secrets from") Long id,
            @Name("tunnel")
                    @Description("What to try, or nothing for the saved one as it is")
                    @Valid
                    TunnelRequest tunnel) {
        return tunnel == null ? service.check(id) : service.check(id, tunnel);
    }
}
