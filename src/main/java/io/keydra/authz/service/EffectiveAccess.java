package io.keydra.authz.service;

import io.keydra.authz.dto.AuthzDtos.EffectivePermissions;
import io.keydra.authz.entity.Permission;
import io.keydra.connections.service.ConnectionService;
import io.keydra.security.service.SecuritySettings;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the caller may do, worked out for every target at once.
 *
 * <p>Here rather than in a resource, because both surfaces need the same answer and a resolver
 * calling a resource would be a door into a door. What it answers is what stops the interface
 * offering actions that would be refused — a page asking per button would be a request per button.
 */
@ApplicationScoped
public class EffectiveAccess {

    private final CallerPermissions caller;
    private final ConnectionService connections;
    private final SecurityIdentity identity;
    private final SecuritySettings settings;

    @Inject
    EffectiveAccess(
            CallerPermissions caller,
            ConnectionService connections,
            SecurityIdentity identity,
            SecuritySettings settings) {
        this.caller = caller;
        this.connections = connections;
        this.identity = identity;
        this.settings = settings;
    }

    public Uni<EffectivePermissions> permissions() {
        return caller.forConnection(null)
                .flatMap(
                        instance ->
                                connections
                                        .list()
                                        .map(
                                                profiles ->
                                                        profiles.stream()
                                                                .map(one -> one.id())
                                                                .toList())
                                        .flatMap(this::perConnection)
                                        .map(
                                                perConnection ->
                                                        new EffectivePermissions(
                                                                name(),
                                                                settings.enabled(),
                                                                instance,
                                                                perConnection)));
    }

    /**
     * One entry per target the caller can see.
     *
     * <p>Resolved one after another rather than at once: each is a handful of small queries on the
     * same session, and a reactive session is single-threaded — issuing them concurrently is how
     * you get "session is currently executing another query" instead of a faster answer.
     */
    private Uni<Map<String, Set<Permission>>> perConnection(List<Long> ids) {
        Uni<Map<String, Set<Permission>>> resolved =
                Uni.createFrom().item(new HashMap<String, Set<Permission>>());
        for (Long id : ids) {
            resolved =
                    resolved.flatMap(
                            soFar ->
                                    caller.forConnection(id)
                                            .map(
                                                    held -> {
                                                        if (!held.isEmpty()) {
                                                            soFar.put(String.valueOf(id), held);
                                                        }
                                                        return soFar;
                                                    }));
        }
        return resolved;
    }

    private String name() {
        return identity.isAnonymous() || identity.getPrincipal() == null
                ? "anonymous"
                : identity.getPrincipal().getName();
    }
}
