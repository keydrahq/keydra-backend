package io.keydra.admin.graphql;

import io.keydra.admin.dto.SettingChange;
import io.keydra.admin.service.ServerAdminService;
import io.keydra.analysis.dto.KeyspaceReport;
import io.keydra.analysis.service.KeyspaceAnalyser;
import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.common.graphql.OneAtATime;
import io.keydra.engine.AclUser;
import io.keydra.engine.Capabilities;
import io.keydra.engine.Database;
import io.keydra.engine.PersistenceState;
import io.keydra.engine.ServerSetting;
import io.keydra.keys.service.DatabaseService;
import io.keydra.security.Roles;
import io.keydra.security.dto.AclUserRequest;
import io.keydra.security.service.AclService;
import io.keydra.topology.dto.TargetTopology;
import io.keydra.topology.service.TopologyService;
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
 * What a target is, underneath: its settings, its shape, its accounts, its keyspace.
 *
 * <p>Five resources gathered behind one surface because they answer one screen's worth of questions
 * about one server. They stay five services below this; nothing is merged.
 *
 * <p>Guarded per operation, never once on the class. Reading a server's settings, changing one,
 * reading its ACL and writing to its ACL are four different permissions, and one annotation over
 * all of them would have to be the loosest — which is how somebody who may look ends up able to
 * rewrite an access list.
 *
 * <p>Transport only, calling the same services the resources call.
 */
@GraphQLApi
@OneAtATime
public class ServerQueries {

    private final ServerAdminService admin;
    private final AclService acl;
    private final TopologyService topology;
    private final KeyspaceAnalyser analyser;
    private final DatabaseService databases;

    @Inject
    ServerQueries(
            ServerAdminService admin,
            AclService acl,
            TopologyService topology,
            KeyspaceAnalyser analyser,
            DatabaseService databases) {
        this.admin = admin;
        this.acl = acl;
        this.topology = topology;
        this.analyser = analyser;
        this.databases = databases;
    }

    // --- What the server is set to ------------------------------------------

    @Query("serverSettings")
    @Description("The server's own configuration, as it reports it")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.SERVER_READ, connection = "connectionId")
    public Uni<List<ServerSetting>> serverSettings(
            @Name("connectionId") Long connectionId,
            @Name("glob") @Description("Only settings whose name matches") String glob) {
        return admin.settings(connectionId, glob);
    }

    @Query("persistence")
    @Description("Whether the server is writing to disk, and when it last did")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.SERVER_READ, connection = "connectionId")
    public Uni<PersistenceState> persistence(@Name("connectionId") Long connectionId) {
        return admin.persistence(connectionId);
    }

    @Query("databases")
    @Description("The numbered databases on a target, with how many keys each holds")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.CONNECTION_VIEW, connection = "connectionId")
    public Uni<List<Database>> databases(@Name("connectionId") Long connectionId) {
        return databases.list(connectionId);
    }

    @Query("capabilities")
    @Description("What a target can do, which decides which tools are offered for it")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.CONNECTION_VIEW, connection = "connectionId")
    public Uni<Capabilities> capabilities(@Name("connectionId") Long connectionId) {
        return topology.capabilities(connectionId);
    }

    @Query("topology")
    @Description("The shape of the target: standalone, replicated, sentinel or clustered")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.CONNECTION_VIEW, connection = "connectionId")
    public Uni<TargetTopology> topology(@Name("connectionId") Long connectionId) {
        return topology.describe(connectionId);
    }

    /**
     * What is in the keyspace, by sampling it.
     *
     * <p>A query rather than a mutation despite the work: it reads, changes nothing, and asking
     * twice gives the same kind of answer. Expensive is a reason to ask rarely, not a reason to
     * call it something it is not.
     */
    @Query("keyspaceReport")
    @Description("What the keyspace holds, worked out by sampling it")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.ANALYSIS_READ, connection = "connectionId")
    public Uni<KeyspaceReport> keyspaceReport(
            @Name("connectionId") Long connectionId,
            @Name("database") @Description("Which database, or the profile's own") Integer database,
            @Name("sample") @Description("How many keys to look at") Integer sample) {
        return analyser.analyse(connectionId, database, sample);
    }

    // --- Who the server lets in ---------------------------------------------

    @Query("aclUsers")
    @Description("The accounts the server itself knows about, and what each may run")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(value = Permission.ACL_READ, connection = "connectionId")
    public Uni<List<AclUser>> aclUsers(@Name("connectionId") Long connectionId) {
        return acl.users(connectionId);
    }

    @Query("aclCategories")
    @Description("The command categories a rule can name, as this server spells them")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(value = Permission.ACL_READ, connection = "connectionId")
    public Uni<List<String>> aclCategories(@Name("connectionId") Long connectionId) {
        return acl.categories(connectionId);
    }

    // --- Changing any of it -------------------------------------------------

    @Mutation("changeServerSetting")
    @Description("Changes one setting on the running server")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.SERVER_CONFIGURE, connection = "connectionId")
    public Uni<Boolean> changeServerSetting(
            @Name("connectionId") Long connectionId, @Name("change") @Valid SettingChange change) {
        return admin.change(connectionId, change).replaceWith(true);
    }

    /**
     * Writes the running configuration to the server's own file.
     *
     * <p>Separate from changing a setting, because they are separate decisions: a change that is
     * not persisted is gone at the next restart, and that is sometimes exactly what somebody wants.
     */
    @Mutation("persistServerSettings")
    @Description("Writes the running configuration to the server's own file")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.SERVER_CONFIGURE, connection = "connectionId")
    public Uni<Boolean> persistServerSettings(@Name("connectionId") Long connectionId) {
        return admin.persistSettings(connectionId).replaceWith(true);
    }

    @Mutation("takeSnapshot")
    @Description("Asks the server to write a snapshot in the background")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.SERVER_CONFIGURE, connection = "connectionId")
    public Uni<Boolean> takeSnapshot(@Name("connectionId") Long connectionId) {
        return admin.snapshot(connectionId).replaceWith(true);
    }

    @Mutation("rewriteAppendLog")
    @Description("Asks the server to rewrite its append-only file")
    @RolesAllowed({Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.SERVER_CONFIGURE, connection = "connectionId")
    public Uni<Boolean> rewriteAppendLog(@Name("connectionId") Long connectionId) {
        return admin.rewriteLog(connectionId).replaceWith(true);
    }

    @Mutation("setAclUser")
    @Description("Creates or replaces one of the server's own accounts")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(value = Permission.ACL_MANAGE, connection = "connectionId")
    public Uni<Boolean> setAclUser(
            @Name("connectionId") Long connectionId, @Name("user") @Valid AclUserRequest user) {
        return acl.setUser(connectionId, user.username(), user.rules()).replaceWith(true);
    }

    @Mutation("deleteAclUser")
    @Description("Removes one of the server's own accounts")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(value = Permission.ACL_MANAGE, connection = "connectionId")
    public Uni<Boolean> deleteAclUser(
            @Name("connectionId") Long connectionId, @Name("username") String username) {
        return acl.deleteUser(connectionId, username);
    }
}
