package io.keydra.security.graphql;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.dto.AuthzDtos.EffectivePermissions;
import io.keydra.authz.dto.CallerAccess;
import io.keydra.authz.dto.SessionSummary;
import io.keydra.authz.dto.TargetPermissions;
import io.keydra.authz.entity.Permission;
import io.keydra.authz.service.EffectiveAccess;
import io.keydra.authz.service.LocalIdentities;
import io.keydra.authz.service.Sessions;
import io.keydra.common.graphql.OneAtATime;
import io.keydra.security.Roles;
import io.keydra.security.dto.CurrentUser;
import io.keydra.security.dto.SecretRotationDtos.RotationResult;
import io.keydra.security.dto.SecretRotationDtos.RotationStatus;
import io.keydra.security.service.SecretRotation;
import io.quarkus.security.Authenticated;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.vertx.http.runtime.CurrentVertxRequest;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.graphql.DefaultValue;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Mutation;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

/**
 * What the person asking is, where they are signed in, and what protects what they saved.
 *
 * <p>Three things a settings page shows about the caller rather than about a target. They are
 * guarded differently and deliberately so: knowing who you are needs only being signed in; ending
 * your own sessions likewise, because an account with no grants can still be signed in on a laptop
 * somebody left somewhere; re-encrypting the store is an administrator's, and a separate permission
 * from every other administrative thing.
 *
 * <p>There is no operation for reading anybody else's sessions. What a person's sessions say is
 * where they work and when.
 */
@GraphQLApi
@OneAtATime
public class AccountQueries {

    private final SecurityIdentity identity;
    private final LocalIdentities identities;
    private final Sessions sessions;
    private final SecretRotation rotation;
    private final EffectiveAccess access;
    private final CurrentVertxRequest request;
    private final boolean securityEnabled;

    @Inject
    AccountQueries(
            SecurityIdentity identity,
            LocalIdentities identities,
            Sessions sessions,
            SecretRotation rotation,
            EffectiveAccess access,
            CurrentVertxRequest request,
            @ConfigProperty(name = "keydra.security.enabled", defaultValue = "true")
                    boolean securityEnabled) {
        this.identity = identity;
        this.identities = identities;
        this.sessions = sessions;
        this.rotation = rotation;
        this.access = access;
        this.request = request;
        this.securityEnabled = securityEnabled;
    }

    /** The account asking, or null when nobody is. */
    private Uni<Long> currentUserId() {
        if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
            return Uni.createFrom().nullItem();
        }
        return identities.userIdOf(identity.getPrincipal().getName());
    }

    /** Which session is reading this, so the list can mark it. */
    private String currentSession() {
        return Sessions.presented(request.getCurrent());
    }

    /**
     * Who is asking.
     *
     * <p>Open to anybody signed in, and answers for an open instance too — where it says security
     * is off, which is what stops an unsecured instance looking like a secured one.
     */
    @Query("me")
    @Description("Who is asking, the roles they hold, and whether access is being enforced")
    @Authenticated
    public Uni<CurrentUser> me() {
        return Uni.createFrom()
                .item(
                        new CurrentUser(
                                identity == null || identity.isAnonymous()
                                        ? null
                                        : identity.getPrincipal().getName(),
                                identity == null ? List.of() : List.copyOf(identity.getRoles()),
                                securityEnabled));
    }

    /**
     * What the caller may do, per target.
     *
     * <p>Asked once for the whole application rather than per button: a page that asked "may I?"
     * for each action would be a request per action, and the answer for every target at once is one
     * query on the server.
     */
    @Query("effectivePermissions")
    @Description("What the caller may do, over Keydra itself and over each target")
    @Authenticated
    public Uni<CallerAccess> effectivePermissions() {
        return access.permissions().map(AccountQueries::asGraph);
    }

    /** The REST answer, reshaped: a map has no GraphQL type, a list of named pairs has. */
    private static CallerAccess asGraph(EffectivePermissions held) {
        return new CallerAccess(
                held.username(),
                held.securityEnabled(),
                List.copyOf(held.instance()),
                held.connections().entrySet().stream()
                        .map(
                                entry ->
                                        new TargetPermissions(
                                                Long.valueOf(entry.getKey()),
                                                List.copyOf(entry.getValue())))
                        .toList());
    }

    /**
     * A page of them, with the one reading this first.
     *
     * <p>Ordered so that the row carrying the action that signs somebody out cannot end up on a
     * page they have to go looking for. That is decided in the query rather than here, because the
     * other surface asks the same question and would otherwise have to remember to sort it too.
     */
    @Query("mySessions")
    @Description("Every browser that can act as you right now, the one reading this first")
    @Authenticated
    public Uni<List<SessionSummary>> mySessions(
            @Name("first") @DefaultValue("20") @Description("How many rows to return") int first,
            @Name("offset") @DefaultValue("0") @Description("How many to skip") int offset) {
        String current = currentSession();
        return currentUserId().flatMap(userId -> sessions.mine(userId, current, first, offset));
    }

    @Query("mySessionCount")
    @Description("How many browsers there are to page through")
    @Authenticated
    public Uni<Long> mySessionCount() {
        return currentUserId().flatMap(sessions::mineCount);
    }

    @Mutation("endSession")
    @Description("Ends one of your sessions; it stops on that browser's next request")
    @Authenticated
    public Uni<Boolean> endSession(@Name("id") String id) {
        return currentUserId().flatMap(userId -> sessions.endMine(id, userId));
    }

    @Mutation("endOtherSessions")
    @Description("Ends every session but this one, and answers how many")
    @Authenticated
    public Uni<Integer> endOtherSessions() {
        String current = currentSession();
        return currentUserId().flatMap(userId -> sessions.endMyOthers(userId, current));
    }

    @Query("encryptionStatus")
    @Description("Which key the stored secrets are under, and how many are not")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.CRYPTO_ROTATE)
    public Uni<RotationStatus> encryptionStatus() {
        return rotation.status();
    }

    @Mutation("reencryptSecrets")
    @Description("Rewrites every stored secret under the current key")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.CRYPTO_ROTATE)
    public Uni<RotationResult> reencryptSecrets() {
        return rotation.rotate();
    }
}
