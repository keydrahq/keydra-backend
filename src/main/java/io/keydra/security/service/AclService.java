package io.keydra.security.service;

import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.service.ConnectionService;
import io.keydra.engine.AccessControl;
import io.keydra.engine.AclUser;
import io.keydra.engine.EngineSelector;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.function.BiFunction;

/** Manages the users a target itself knows about. */
@ApplicationScoped
public class AclService {

    private final ConnectionService connections;
    private final EngineSelector engines;

    @Inject
    AclService(ConnectionService connections, EngineSelector engines) {
        this.connections = connections;
        this.engines = engines;
    }

    public Uni<List<AclUser>> users(Long connectionId) {
        return withAccessControl(connectionId, (profile, acl) -> acl.users(profile));
    }

    public Uni<Void> setUser(Long connectionId, String username, List<String> rules) {
        return withAccessControl(
                connectionId, (profile, acl) -> acl.setUser(profile, username, rules));
    }

    public Uni<Boolean> deleteUser(Long connectionId, String username) {
        return withAccessControl(connectionId, (profile, acl) -> acl.deleteUser(profile, username));
    }

    public Uni<List<String>> categories(Long connectionId) {
        return withAccessControl(connectionId, (profile, acl) -> acl.categories(profile));
    }

    private <T> Uni<T> withAccessControl(
            Long connectionId, BiFunction<ConnectionProfile, AccessControl, Uni<T>> work) {
        return connections
                .load(connectionId)
                .flatMap(
                        profile ->
                                engines.forProfile(profile)
                                        .accessControl()
                                        .map(acl -> work.apply(profile, acl))
                                        .orElseGet(
                                                () ->
                                                        Uni.createFrom()
                                                                .failure(
                                                                        new UnsupportedOperationException(
                                                                                "This target keeps"
                                                                                    + " no users"))));
    }
}
