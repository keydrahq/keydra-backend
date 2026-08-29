package io.keydra.authz.graphql;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.dto.AuthzDtos.GrantRequest;
import io.keydra.authz.dto.AuthzDtos.GrantSummary;
import io.keydra.authz.dto.AuthzDtos.GroupRequest;
import io.keydra.authz.dto.AuthzDtos.GroupSummary;
import io.keydra.authz.dto.AuthzDtos.MembershipRequest;
import io.keydra.authz.dto.AuthzDtos.RoleRequest;
import io.keydra.authz.dto.AuthzDtos.RoleSummary;
import io.keydra.authz.dto.AuthzDtos.ServerGroupRequest;
import io.keydra.authz.dto.AuthzDtos.ServerGroupSummary;
import io.keydra.authz.dto.AuthzDtos.SignInPolicyState;
import io.keydra.authz.dto.AuthzDtos.UserRequest;
import io.keydra.authz.dto.AuthzDtos.UserSummary;
import io.keydra.authz.dto.InvitationIssued;
import io.keydra.authz.dto.PermissionInfo;
import io.keydra.authz.entity.AccountInvitation;
import io.keydra.authz.entity.Permission;
import io.keydra.authz.service.AuthzAdminService;
import io.keydra.authz.service.CallerPermissions;
import io.keydra.authz.service.Invitations;
import io.keydra.authz.service.PublicUrl;
import io.keydra.authz.service.SignInPolicies;
import io.keydra.common.graphql.OneAtATime;
import io.keydra.security.Audited;
import io.keydra.security.Roles;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import java.util.Arrays;
import java.util.List;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

/**
 * Who exists, what they belong to, and what that is worth.
 *
 * <p>Six resources in one place, because the page is one page: an access screen shows accounts,
 * groups, roles, grants and server groups together, and each of those used to be its own request.
 * They stay six services below this — nothing is merged, only asked for at once.
 *
 * <p>Every operation is annotated individually rather than once on the class, because they do not
 * all need the same thing. Managing accounts, managing groups and managing grants are three
 * separate permissions, and a single annotation covering all of them would have to pick the loosest
 * — which is how somebody who may add a group ends up able to delete an account.
 *
 * <p>Transport only, calling the same services the resources call.
 */
@GraphQLApi
@OneAtATime
public class AccessQueries {

    private final AuthzAdminService service;
    private final Invitations invitations;
    private final SignInPolicies policies;
    private final CallerPermissions permissions;
    private final PublicUrl publicUrl;
    private final SecurityIdentity caller;
    private final CurrentVertxRequest request;

    @Inject
    AccessQueries(
            AuthzAdminService service,
            Invitations invitations,
            SignInPolicies policies,
            CallerPermissions permissions,
            PublicUrl publicUrl,
            SecurityIdentity caller,
            CurrentVertxRequest request) {
        this.service = service;
        this.invitations = invitations;
        this.policies = policies;
        this.permissions = permissions;
        this.publicUrl = publicUrl;
        this.caller = caller;
        this.request = request;
    }

    /** Where the browser asking reached Keydra, for a link somebody has to be able to open. */
    private String reachedAt() {
        return publicUrl.of(request.getCurrent().request());
    }

    /** Who is asking, as a name rather than an id, so it reads as itself in a record later. */
    private String whoIsAsking() {
        return caller == null || caller.isAnonymous() ? null : caller.getPrincipal().getName();
    }

    // --- Accounts -----------------------------------------------------------

    @Query("accounts")
    @Description("Every account, with the roles it holds")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.USERS_MANAGE)
    public Uni<List<UserSummary>> accounts() {
        return service.users();
    }

    @Mutation("createAccount")
    @Description("Adds an account; the password is set by whoever accepts the invitation")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.USERS_MANAGE)
    public Uni<UserSummary> createAccount(@Name("account") @Valid UserRequest account) {
        return service.createUser(account);
    }

    @Mutation("updateAccount")
    @Description("Changes an account")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.USERS_MANAGE)
    public Uni<UserSummary> updateAccount(
            @Name("id") Long id, @Name("account") @Valid UserRequest account) {
        return service.updateUser(id, account);
    }

    /** Answers whether there was one to remove; deleting something twice is not an error. */
    @Mutation("deleteAccount")
    @Description("Removes an account and every grant it held")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.USERS_MANAGE)
    public Uni<Boolean> deleteAccount(@Name("id") Long id) {
        return service.deleteUser(id);
    }

    // --- Groups of people ---------------------------------------------------

    @Query("groups")
    @Description("Every group, with who is in it")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.GROUPS_MANAGE)
    public Uni<List<GroupSummary>> groups() {
        return service.groups();
    }

    @Mutation("createGroup")
    @Description("Adds a group")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.GROUPS_MANAGE)
    public Uni<GroupSummary> createGroup(@Name("group") @Valid GroupRequest group) {
        return service.createGroup(group);
    }

    @Mutation("deleteGroup")
    @Description("Removes a group and every grant it held")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.GROUPS_MANAGE)
    public Uni<Boolean> deleteGroup(@Name("id") Long id) {
        return service.deleteGroup(id);
    }

    @Mutation("addGroupMember")
    @Description("Puts an account, or another group, inside a group")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.GROUPS_MANAGE)
    public Uni<Boolean> addGroupMember(
            @Name("groupId") Long groupId, @Name("member") @Valid MembershipRequest member) {
        return service.addMember(groupId, member).replaceWith(true);
    }

    @Mutation("removeGroupMember")
    @Description("Takes a member out of a group")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.GROUPS_MANAGE)
    public Uni<Boolean> removeGroupMember(@Name("membershipId") Long membershipId) {
        return service.removeMember(membershipId);
    }

    // --- Groups of servers --------------------------------------------------

    @Query("serverGroups")
    @Description("Every server group, with which targets are in it")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.GROUPS_MANAGE)
    public Uni<List<ServerGroupSummary>> serverGroups() {
        return service.serverGroups();
    }

    @Mutation("createServerGroup")
    @Description("Adds a server group, so a grant can name several targets at once")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.GROUPS_MANAGE)
    public Uni<ServerGroupSummary> createServerGroup(
            @Name("group") @Valid ServerGroupRequest group) {
        return service.createServerGroup(group);
    }

    @Mutation("deleteServerGroup")
    @Description("Removes a server group and every grant scoped to it")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.GROUPS_MANAGE)
    public Uni<Boolean> deleteServerGroup(@Name("id") Long id) {
        return service.deleteServerGroup(id);
    }

    @Mutation("addServerToGroup")
    @Description("Puts a target in a server group")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.GROUPS_MANAGE)
    public Uni<Boolean> addServerToGroup(
            @Name("groupId") Long groupId, @Name("connectionId") Long connectionId) {
        return service.addServerToGroup(groupId, connectionId).replaceWith(true);
    }

    @Mutation("removeServerFromGroup")
    @Description("Takes a target out of a server group")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.GROUPS_MANAGE)
    public Uni<Boolean> removeServerFromGroup(
            @Name("groupId") Long groupId, @Name("connectionId") Long connectionId) {
        return service.removeServerFromGroup(groupId, connectionId);
    }

    // --- Roles and what they carry ------------------------------------------

    @Query("roles")
    @Description("Every role, built in or defined here, with the permissions it carries")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.GRANTS_MANAGE)
    public Uni<List<RoleSummary>> roles() {
        return service.roles();
    }

    /**
     * Every permission there is.
     *
     * <p>A plain list rather than a connection: it is an enum, so it says nothing about this
     * instance and everything about this build.
     */
    @Query("permissionCatalogue")
    @Description("Every permission a role can carry, with the level it applies at")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.GRANTS_MANAGE)
    public Uni<List<PermissionInfo>> permissionCatalogue() {
        return Uni.createFrom()
                .item(
                        Arrays.stream(Permission.values())
                                .map(
                                        permission ->
                                                new PermissionInfo(
                                                        permission.name(),
                                                        permission.id(),
                                                        permission.level().name()))
                                .toList());
    }

    @Mutation("createRole")
    @Description("Defines a role")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.GRANTS_MANAGE)
    public Uni<RoleSummary> createRole(@Name("role") @Valid RoleRequest role) {
        return service.createRole(role);
    }

    @Mutation("updateRole")
    @Description("Changes what a role carries")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.GRANTS_MANAGE)
    public Uni<RoleSummary> updateRole(@Name("id") Long id, @Name("role") @Valid RoleRequest role) {
        return service.updateRole(id, role);
    }

    @Mutation("deleteRole")
    @Description("Removes a role and every grant of it")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.GRANTS_MANAGE)
    public Uni<Boolean> deleteRole(@Name("id") Long id) {
        return service.deleteRole(id);
    }

    // --- Grants -------------------------------------------------------------

    @Query("grants")
    @Description("Who holds which role, over what")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.GRANTS_MANAGE)
    public Uni<List<GrantSummary>> grants() {
        return service.grants();
    }

    @Mutation("grant")
    @Description("Gives a role to an account or a group, over an instance, group or target")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.GRANTS_MANAGE)
    public Uni<GrantSummary> grant(@Name("grant") @Valid GrantRequest grant) {
        return service.grant(grant);
    }

    // --- Getting somebody in ------------------------------------------------

    /**
     * Makes a link that lets somebody set their own password.
     *
     * <p>Answers the link as well as whether it was mailed, because mail is not always configured
     * and an administrator who cannot send one still needs a way to hand it over. Shown once; what
     * is stored is a hash of it.
     *
     * <p>The address the link points at comes from the request, so it is the address the browser
     * asking for it reached Keydra at rather than the port this process happens to listen on.
     */
    @Mutation("inviteAccount")
    @Description("Makes a link that lets an account set its own password")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.USERS_MANAGE)
    @Audited("user.invite")
    public Uni<InvitationIssued> inviteAccount(@Name("id") Long id) {
        return invitations
                .invite(id, AccountInvitation.Purpose.INVITATION, whoIsAsking())
                .map(
                        issued ->
                                new InvitationIssued(
                                        issued.mailed(),
                                        issued.address(),
                                        publicUrl
                                                .absolute("/invitation/" + issued.token())
                                                .orElseGet(
                                                        () ->
                                                                reachedAt()
                                                                        + "/invitation/"
                                                                        + issued.token())));
    }

    @Mutation("revoke")
    @Description("Takes a grant back; it stops applying on the holder's next request")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.GRANTS_MANAGE)
    public Uni<Boolean> revoke(@Name("id") Long id) {
        return service.revoke(id);
    }

    // --- What the instance asks of everybody ---------------------------------

    /**
     * What this installation asks of whoever signs in.
     *
     * <p>Beside the accounts rather than in the settings a person keeps for themselves: this is a
     * decision about everybody, made by somebody looking at a page that says how many people it
     * reaches.
     */
    @Query("signInPolicy")
    @Description("What this instance asks of whoever signs in")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.POLICY_MANAGE)
    public Uni<SignInPolicyState> signInPolicy() {
        return policies.state();
    }

    /**
     * Requires a second factor, or stops requiring one.
     *
     * <p>Turning it on is refused unless the caller has paired an authenticator of their own. That
     * check is the whole difference between a switch and a way to lose an installation: requiring a
     * factor takes the roles away from every account that has not paired one, and without the guard
     * the first such account is the one that flipped the switch.
     */
    @Mutation("requireSecondFactor")
    @Description("Require a second factor of every local account, or stop requiring one")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.POLICY_MANAGE)
    @Audited("policy.second-factor")
    public Uni<SignInPolicyState> requireSecondFactor(@Name("required") boolean required) {
        return permissions
                .currentUserId()
                .flatMap(userId -> policies.requireSecondFactor(required, userId, whoIsAsking()));
    }
}
