package io.keydra.admin.service;

import io.keydra.admin.dto.SettingChange;
import io.keydra.admin.exception.AdminUnsupportedException;
import io.keydra.admin.exception.SettingRefusedException;
import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.service.ConnectionService;
import io.keydra.engine.EngineSelector;
import io.keydra.engine.PersistenceState;
import io.keydra.engine.ServerAdmin;
import io.keydra.engine.ServerSetting;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Administering a target: what it is configured to do, and how it keeps its data.
 *
 * <p>Everything here changes the server rather than the data in it. A page that merely reads is
 * harmless; a page that writes is one badly chosen maxmemory policy away from evicting keys nobody
 * asked to expire, which is why the endpoints above this are for operators and are audited.
 */
@ApplicationScoped
public class ServerAdminService {

    private final ConnectionService connections;
    private final EngineSelector engines;

    @Inject
    ServerAdminService(ConnectionService connections, EngineSelector engines) {
        this.connections = connections;
        this.engines = engines;
    }

    public Uni<List<ServerSetting>> settings(Long connectionId, String glob) {
        return with(connectionId, (profile, admin) -> admin.settings(profile, glob));
    }

    public Uni<Void> change(Long connectionId, SettingChange change) {
        return with(
                        connectionId,
                        (profile, admin) ->
                                admin.changeSetting(profile, change.name(), change.value()))
                .onFailure()
                // An unknown name or a value out of range is the caller's business, not a
                // fault here — and the server's own words say which it was.
                .transform(refusal -> new SettingRefusedException(change.name(), refusal));
    }

    public Uni<Void> persistSettings(Long connectionId) {
        return with(connectionId, (profile, admin) -> admin.persistSettings(profile));
    }

    public Uni<PersistenceState> persistence(Long connectionId) {
        return with(connectionId, (profile, admin) -> admin.persistence(profile));
    }

    public Uni<Void> snapshot(Long connectionId) {
        return with(connectionId, (profile, admin) -> admin.snapshot(profile));
    }

    public Uni<Void> rewriteLog(Long connectionId) {
        return with(connectionId, (profile, admin) -> admin.rewriteLog(profile));
    }

    /** Loads the profile and its engine's administration, or refuses when it has none. */
    private <T> Uni<T> with(Long connectionId, Action<T> action) {
        return connections
                .load(connectionId)
                .flatMap(
                        profile ->
                                action.run(
                                        profile,
                                        engines.forProfile(profile)
                                                .admin()
                                                .orElseThrow(
                                                        () ->
                                                                new AdminUnsupportedException(
                                                                        profile.engine.name()))));
    }

    @FunctionalInterface
    private interface Action<T> {
        Uni<T> run(ConnectionProfile profile, ServerAdmin admin);
    }
}
