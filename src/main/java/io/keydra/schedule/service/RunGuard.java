package io.keydra.schedule.service;

import io.keydra.authz.entity.Permission;
import io.keydra.authz.persistence.AuthzRepository;
import io.keydra.authz.service.PermissionResolver;
import io.keydra.schedule.entity.ScheduledJob;
import io.keydra.security.service.SecuritySettings;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Whether whoever arranged a job still holds what it needs.
 *
 * <p>Asked at every run and not only when the schedule was written. A schedule is a way of doing
 * something later; without this it would also be a way of keeping access somebody has had taken
 * away, which is the one thing a permission model must not let a feature quietly add.
 *
 * <p>A bean of its own rather than a method on {@link JobRunner} because the question needs a
 * session and the work around it must not have one — and a private method calling itself gets no
 * interceptor, so the boundary has to be a call to another bean to exist at all.
 */
@ApplicationScoped
public class RunGuard {

    private final AuthzRepository accounts;
    private final PermissionResolver permissions;
    private final SecuritySettings settings;

    @Inject
    RunGuard(AuthzRepository accounts, PermissionResolver permissions, SecuritySettings settings) {
        this.accounts = accounts;
        this.permissions = permissions;
        this.settings = settings;
    }

    /**
     * Which permission the run is missing, or null when it is missing none.
     *
     * <p>The permission rather than a yes-or-no, because the run history says why a run was refused
     * and "no longer holds copy:run" and "no longer holds script:run" are two different things to
     * have to fix.
     *
     * <p>One resolution answers both questions. A grant made at instance scope reaches every
     * target, so the set that comes back for this job's target already contains what somebody holds
     * over Keydra itself — asking twice would be two round trips for one answer.
     *
     * <p>A schedule with no author — one seeded, or written before this was recorded — is allowed
     * to run: refusing it would break arrangements nobody can repair, and the alternative reading
     * is that Keydra itself arranged it.
     *
     * <p>An instance that is not enforcing says yes to everything, for the same reason every other
     * check does: without enforcement there are no accounts to resolve the author against, and
     * looking one up would refuse every schedule on an open instance.
     *
     * @param alsoNeeded what this job's own settings ask for on top of its type's permission
     */
    @WithSession
    public Uni<Permission> missing(ScheduledJob job, Set<Permission> alsoNeeded) {
        if (!settings.enabled() || job.createdBy == null || job.createdBy.isBlank()) {
            return Uni.createFrom().nullItem();
        }
        Set<Permission> needed = new LinkedHashSet<>();
        needed.add(job.jobType.required());
        needed.addAll(alsoNeeded);

        return accounts.userByUsername(job.createdBy)
                .flatMap(
                        user ->
                                user == null || !user.enabled
                                        // An account that is gone or switched off holds nothing,
                                        // and the first thing it does not hold is as good an
                                        // answer as any: the run is refused either way.
                                        ? Uni.createFrom().item(needed.iterator().next())
                                        : permissions
                                                .permissionsFor(user.id, job.connectionId)
                                                .map(held -> firstAbsent(needed, held)));
    }

    private static Permission firstAbsent(Set<Permission> needed, Set<Permission> held) {
        return needed.stream().filter(one -> !held.contains(one)).findFirst().orElse(null);
    }
}
