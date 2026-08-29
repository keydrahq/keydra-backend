package io.keydra.approvals.service;

import io.keydra.approvals.entity.ApprovalRequest;
import io.keydra.authz.entity.Permission;
import io.keydra.authz.persistence.AuthzRepository;
import io.keydra.authz.service.PermissionResolver;
import io.keydra.security.service.SecuritySettings;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Whether whoever asked for this still holds what it needs.
 *
 * <p>Asked at the moment the work runs and not only when it was written down. Between the asking
 * and the agreeing there is a gap of hours by design, and without this the gap would be somewhere
 * an access that has been taken away still works — which is the one thing a permission model must
 * not let a feature quietly add. The same rule the schedules have had since phase 10, and the same
 * shape: {@code schedule.service.RunGuard} asks it of a cron and this asks it of a colleague.
 *
 * <p>A bean of its own because the question needs a session and the work around it must not have
 * one, and because a private method calling itself gets no interceptor.
 */
@ApplicationScoped
public class ApprovalGuard {

    private final AuthzRepository accounts;
    private final PermissionResolver permissions;
    private final SecuritySettings settings;
    private final ApprovalWorkshop workshop;

    @Inject
    ApprovalGuard(
            AuthzRepository accounts,
            PermissionResolver permissions,
            SecuritySettings settings,
            ApprovalWorkshop workshop) {
        this.accounts = accounts;
        this.permissions = permissions;
        this.settings = settings;
        this.workshop = workshop;
    }

    /**
     * Which permission the requester is missing, or null when they are missing none.
     *
     * <p>The permission rather than a yes-or-no, because "no longer holds keys:delete" and "no
     * longer holds script:run" are two different things to have to fix, and the request says which
     * on the page where somebody is waiting for it.
     *
     * <p>Both ends of a migration, in turn. A move that is still allowed at the source and no
     * longer allowed at the destination is not half allowed.
     */
    @WithSession
    public Uni<Permission> missing(ApprovalRequest request) {
        if (!settings.enabled() || request.requestedBy == null || request.requestedBy.isBlank()) {
            return Uni.createFrom().nullItem();
        }
        Set<Permission> needed = new LinkedHashSet<>();
        needed.add(request.kind.required());
        needed.addAll(workshop.workFor(request.kind).alsoNeeds(request));

        return accounts.userByUsername(request.requestedBy)
                .flatMap(
                        user -> {
                            if (user == null || !user.enabled) {
                                // An account that is gone or switched off holds nothing, and the
                                // first thing it does not hold is as good an answer as any.
                                return Uni.createFrom().item(needed.iterator().next());
                            }
                            return permissions
                                    .permissionsFor(user.id, request.connectionId)
                                    .flatMap(
                                            here -> {
                                                Permission absent = firstAbsent(needed, here);
                                                if (absent != null
                                                        || request.secondConnectionId == null) {
                                                    return Uni.createFrom().item(absent);
                                                }
                                                return permissions
                                                        .permissionsFor(
                                                                user.id, request.secondConnectionId)
                                                        .map(there -> firstAbsent(needed, there));
                                            });
                        });
    }

    private static Permission firstAbsent(Set<Permission> needed, Set<Permission> held) {
        return needed.stream().filter(one -> !held.contains(one)).findFirst().orElse(null);
    }
}
