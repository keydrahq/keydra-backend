package io.keydra.authz.dto;

import io.keydra.authz.entity.Permission;
import io.keydra.authz.entity.ScopeType;
import io.keydra.authz.entity.SubjectType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The wire shapes for managing who may do what.
 *
 * <p>Gathered in one file because they are one contract: a page that grants a role reads users,
 * groups, scopes and roles to do it, and splitting six records across six files would say they were
 * six ideas.
 */
public final class AuthzDtos {

    private AuthzDtos() {}

    /**
     * Somebody who signs in.
     *
     * @param groups the groups they are directly in, by name, since an id means nothing on screen
     */
    @Schema(name = "UserSummary", description = "Somebody who signs in")
    public record UserSummary(
            Long id,
            String username,
            String displayName,
            String email,
            String provider,
            boolean enabled,
            boolean hasPassword,
            Instant lastSeenAt,
            List<String> groups) {}

    /** A user to create. A password is only meaningful for a local account. */
    @Schema(name = "UserRequest", description = "A user to create or change")
    public record UserRequest(
            @NotBlank String username,
            String displayName,
            String email,
            @Size(min = 12, message = "A password shorter than twelve characters is a guess away")
                    String password,
            Boolean enabled) {}

    @Schema(name = "GroupSummary", description = "A named set of people, and of other sets")
    public record GroupSummary(
            Long id,
            String name,
            String description,
            String managedBy,
            List<String> memberUsers,
            List<String> memberGroups) {}

    @Schema(name = "GroupRequest", description = "A group to create or change")
    public record GroupRequest(@NotBlank String name, String description) {}

    @Schema(name = "ServerGroupSummary", description = "A named set of targets")
    public record ServerGroupSummary(
            Long id, String name, String description, Long parentId, List<Long> connectionIds) {}

    @Schema(name = "ServerGroupRequest", description = "A server group to create or change")
    public record ServerGroupRequest(@NotBlank String name, String description, Long parentId) {}

    /**
     * A named bundle of permissions.
     *
     * @param builtIn whether it is one of the three that cannot be edited
     */
    @Schema(name = "RoleSummary", description = "A named bundle of permissions")
    public record RoleSummary(
            Long id,
            String name,
            String description,
            boolean builtIn,
            Set<Permission> permissions) {}

    /**
     * Where somebody stands with a second factor.
     *
     * @param enabled whether one is on — which is confirmed, not merely begun
     * @param recoveryCodesLeft how many are unspent, so somebody can be told before they run out
     */
    @Schema(name = "SecondFactorState", description = "Where you stand with a second factor")
    public record SecondFactorState(boolean enabled, long recoveryCodesLeft) {}

    /**
     * A pairing that has been begun and not yet proved.
     *
     * @param secret the shared secret, base32 — the only time it leaves the server
     * @param uri what an authenticator is given, which is what the QR code encodes
     */
    @Schema(name = "SecondFactorSetup", description = "A pairing waiting to be proved")
    public record SecondFactorSetup(String secret, String uri) {}

    /** One code, proving the pairing worked. */
    @Schema(name = "SecondFactorConfirmation", description = "The code from the authenticator")
    public record SecondFactorConfirmation(@NotBlank String code) {}

    /**
     * What this installation asks of everybody who signs in.
     *
     * @param secondFactorRequired whether a local account must have a confirmed authenticator
     * @param changedAt when the switch was last moved, or null while nobody has moved it
     * @param changedBy who moved it, by name — the audit log holds the event, and this line has to
     *     still read sensibly after that account has been deleted
     * @param accountsOwingAFactor how many accounts the requirement reaches and that have not
     *     enrolled. The number the page exists to show: turning this on without it is a switch
     *     pressed at the wrong time of day.
     */
    @Schema(name = "SignInPolicyState", description = "What this instance asks of whoever signs in")
    public record SignInPolicyState(
            boolean secondFactorRequired,
            Instant changedAt,
            String changedBy,
            long accountsOwingAFactor) {}

    /** One switch, moved. */
    @Schema(name = "SignInPolicyRequest", description = "What to ask of whoever signs in")
    public record SignInPolicyRequest(boolean secondFactorRequired) {}

    /**
     * The codes that get somebody back in without their phone.
     *
     * <p>Returned once. Nothing can show them again — only their hashes are kept.
     */
    @Schema(name = "RecoveryCodes", description = "Shown once and never again")
    public record RecoveryCodes(java.util.List<String> codes) {}

    @Schema(name = "RoleRequest", description = "A custom role to create or change")
    public record RoleRequest(
            @NotBlank String name, String description, @NotNull Set<Permission> permissions) {}

    /**
     * The sentence: this subject holds this role on this scope.
     *
     * <p>The names are carried beside the ids because a grants page is read rather than joined: an
     * administrator looking at a row needs to see "payments-devs / operator / payments-servers",
     * not three numbers.
     */
    @Schema(name = "GrantSummary", description = "This subject holds this role on this scope")
    public record GrantSummary(
            Long id,
            SubjectType subjectType,
            Long subjectId,
            String subjectName,
            ScopeType scopeType,
            Long scopeId,
            String scopeName,
            Long roleId,
            String roleName,
            Instant grantedAt,
            String grantedBy) {}

    @Schema(name = "GrantRequest", description = "A grant to make")
    public record GrantRequest(
            @NotNull SubjectType subjectType,
            @NotNull Long subjectId,
            @NotNull ScopeType scopeType,
            Long scopeId,
            @NotNull Long roleId) {}

    /**
     * Whether there is anything to log into, and whether anybody has.
     *
     * <p>The login page asks this before it draws itself. Three answers matter and they look
     * nothing alike: enforcement is off and there is no sign-in at all; enforcement is on but
     * nobody exists yet, so the first thing to do is create an administrator; or there is an
     * instance to sign into normally.
     *
     * @param needsSetup true when enforcement is on and Keydra has no accounts at all
     * @param mustEnrolSecondFactor true when this account is signed in and may do nothing but pair
     *     an authenticator, because the installation requires one and this account has not. The
     *     server refuses on its own — the roles are gone — and this is what lets the browser say
     *     why rather than drawing an application whose every request answers 403.
     */
    @Schema(name = "AuthState", description = "Whether there is anything to sign into")
    public record AuthState(
            boolean securityEnabled,
            boolean needsSetup,
            boolean authenticated,
            String username,
            boolean mustEnrolSecondFactor) {}

    /**
     * The first administrator.
     *
     * <p>Accepted only while there are no accounts, which is what makes an unauthenticated endpoint
     * that creates an administrator safe rather than the worst idea in the file.
     */
    @Schema(name = "SetupRequest", description = "The first administrator")
    public record SetupRequest(
            @NotBlank String username,
            String displayName,
            String email,
            @NotBlank
                    @Size(
                            min = 12,
                            message = "A password shorter than twelve characters is a guess away")
                    String password) {}

    /** One membership to add: a person into a group, or a group into a group. */
    @Schema(name = "MembershipRequest", description = "Something to put in a group")
    public record MembershipRequest(Long userId, Long groupId) {}

    /**
     * What the caller may do, so the interface can stop offering what will be refused.
     *
     * @param instance the permissions they hold over Keydra itself
     * @param connections what they hold per target, keyed by its id as a string because JSON keys
     *     are strings and pretending otherwise costs a conversion at both ends
     */
    @Schema(name = "EffectivePermissions", description = "What the caller may do")
    public record EffectivePermissions(
            String username,
            boolean securityEnabled,
            Set<Permission> instance,
            java.util.Map<String, Set<Permission>> connections) {}
}
