package io.keydra.authz.graphql;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.dto.SignInActivity;
import io.keydra.authz.entity.Permission;
import io.keydra.authz.service.SignInActivityService;
import io.keydra.common.graphql.OneAtATime;
import io.keydra.security.Roles;
import io.quarkus.security.Authenticated;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import org.eclipse.microprofile.graphql.DefaultValue;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.Query;

/**
 * Who has been signing in, and what looked wrong about it.
 *
 * <p>Two audiences asking different questions, so two operations rather than one filtered one. A
 * person reads their own list to check a single fact — is every one of these mine — and needs no
 * role to do it, only to be signed in, for the same reason managing your own sessions needs none:
 * an account with no grants at all can still be signed in on a laptop somebody left somewhere.
 *
 * <p>An administrator reads across everybody's and is looking for a shape: one source working
 * through many names, a run of failures that ended in a success, an account used from a country it
 * has never been used from. That needs {@code AUDIT_READ}, which is the permission that already
 * means "may read what has been happening here".
 *
 * <p>There is no operation for reading somebody else's own list. What a person's sign-ins say is
 * where they work and when, and the flagged list already says everything an administrator needs
 * without saying that.
 */
@GraphQLApi
@OneAtATime
public class SignInActivityQueries {

    /** How far back the two administrator questions look unless asked otherwise. */
    private static final Duration DEFAULT_WINDOW = Duration.ofDays(7);

    private final SignInActivityService activity;

    @Inject
    SignInActivityQueries(SignInActivityService activity) {
        this.activity = activity;
    }

    @Query("mySignIns")
    @Description("Your own recent sign-ins, newest first")
    @Authenticated
    public Uni<List<SignInActivity>> mySignIns(
            @Name("first") @DefaultValue("20") @Description("How many rows to return") int first,
            @Name("offset") @DefaultValue("0") @Description("How many to skip") int offset) {
        return activity.mine(first, offset);
    }

    @Query("mySignInCount")
    @Description("How many sign-ins there are to page through")
    @Authenticated
    public Uni<Long> mySignInCount() {
        return activity.mineCount();
    }

    @Query("flaggedSignIns")
    @Description("Sign-ins that worked but did not look like the ones before them")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.AUDIT_READ)
    public Uni<List<SignInActivity>> flaggedSignIns(
            @Name("days") @DefaultValue("7") @Description("How far back to look") int days,
            @Name("first") @DefaultValue("50") @Description("How many rows to return") int first,
            @Name("offset") @DefaultValue("0") @Description("How many to skip") int offset) {
        return activity.flagged(window(days), first, offset);
    }

    @Query("flaggedSignInCount")
    @Description("How many flagged sign-ins there are in the window")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.AUDIT_READ)
    public Uni<Long> flaggedSignInCount(
            @Name("days") @DefaultValue("7") @Description("How far back to look") int days) {
        return activity.flaggedCount(window(days));
    }

    @Query("refusedSignIns")
    @Description("Sign-ins that were refused, newest first")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.AUDIT_READ)
    public Uni<List<SignInActivity>> refusedSignIns(
            @Name("days") @DefaultValue("1") @Description("How far back to look") int days,
            @Name("first") @DefaultValue("50") @Description("How many rows to return") int first) {
        return activity.failures(window(days), first);
    }

    /** Bounded, because a window is a window and a caller does not get the lot. */
    private static Duration window(int days) {
        int bounded = Math.max(1, Math.min(days, 90));
        return days <= 0 ? DEFAULT_WINDOW : Duration.ofDays(bounded);
    }
}
