package io.keydra.connections.service;

import io.keydra.authz.service.CallerPermissions;
import io.keydra.common.tls.Certificates;
import io.keydra.connections.dto.ConnectionRemoved;
import io.keydra.connections.dto.ConnectionRequest;
import io.keydra.connections.dto.ConnectionResponse;
import io.keydra.connections.dto.ConnectionState;
import io.keydra.connections.dto.ConnectionStatus;
import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.exception.ConnectionNotFoundException;
import io.keydra.connections.exception.DuplicateConnectionNameException;
import io.keydra.connections.exception.InvalidConnectionException;
import io.keydra.connections.mapper.ConnectionMapper;
import io.keydra.connections.persistence.ConnectionProfileRepository;
import io.keydra.connections.registry.ConnectionRegistry;
import io.keydra.console.service.CommandPolicy;
import io.keydra.engine.EngineSelector;
import io.keydra.engine.EngineType;
import io.keydra.events.dto.NotificationCategory;
import io.keydra.events.service.NotificationHub;
import io.keydra.events.service.SocketAudience;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.jboss.logging.Logger;

/**
 * Business rules for connection profiles: uniqueness, persistence, keeping the registry in step and
 * announcing changes.
 *
 * <p>Fully reactive — every method returns a {@link Uni} and the session or transaction is scoped
 * by {@link WithSession} / {@link WithTransaction}, so nothing ever blocks an event-loop thread.
 * Kept apart from the resource so these rules are not entangled with HTTP concerns.
 */
@ApplicationScoped
public class ConnectionService {

    private static final Logger LOG = Logger.getLogger(ConnectionService.class);

    private final ConnectionProfileRepository repository;
    private final ConnectionRegistry registry;
    private final SocketAudience audience;
    private final ConnectionMapper mapper;
    private final NotificationHub hub;
    private final CallerPermissions caller;
    private final EngineSelector engines;
    private final jakarta.enterprise.event.Event<ConnectionRemoved> removed;

    @Inject
    ConnectionService(
            ConnectionProfileRepository repository,
            ConnectionRegistry registry,
            SocketAudience audience,
            ConnectionMapper mapper,
            NotificationHub hub,
            CallerPermissions caller,
            EngineSelector engines,
            jakarta.enterprise.event.Event<ConnectionRemoved> removed) {
        this.repository = repository;
        this.registry = registry;
        this.audience = audience;
        this.mapper = mapper;
        this.hub = hub;
        this.caller = caller;
        this.engines = engines;
        this.removed = removed;
    }

    /**
     * Every target's id, with no filtering at all.
     *
     * <p>The one method here that does not apply the visibility rule, and it exists for the thing
     * that applies it: {@code SocketAudience} works out afresh which of these a person may hear
     * about, and cannot ask for "the ones they can see" because that answer needs the request that
     * has already ended. Everything else uses {@link #list()}.
     */
    @WithSession
    public Uni<List<Long>> everyId() {
        return repository.listAll().map(profiles -> profiles.stream().map(one -> one.id).toList());
    }

    /**
     * Every target the caller can see.
     *
     * <p>Filtered here rather than at the endpoint, so that every page which lists targets — the
     * catalog, the overview, the fleet totals, the migration picker — is filtered by having asked
     * one question. A page that listed them some other way would be a page that showed somebody a
     * server they have no business knowing exists.
     *
     * <p>Visibility is not a separate permission: a target somebody holds anything at all on is one
     * they have a reason to know about, and one they hold nothing on is not.
     */
    @WithSession
    public Uni<List<ConnectionResponse>> list() {
        return repository
                .listAll()
                .invoke(ConnectionService.this::probeUncontacted)
                .flatMap(
                        profiles ->
                                caller.visible(
                                                profiles.stream()
                                                        .map(profile -> profile.id)
                                                        .toList())
                                        .map(
                                                visible ->
                                                        profiles.stream()
                                                                .filter(
                                                                        profile ->
                                                                                visible.contains(
                                                                                        profile.id))
                                                                .map(
                                                                        profile ->
                                                                                mapper.toResponse(
                                                                                        profile,
                                                                                        registry
                                                                                                .status(
                                                                                                        profile.id)))
                                                                .toList()));
    }

    /**
     * Probes profiles that have never been contacted, without waiting for the result.
     *
     * <p>This is what "lazy connect" means in practice: a profile joins the health rotation the
     * first time someone looks at it, so opening the page after a restart fills in every status
     * instead of leaving rows blank until a manual test. Only UNKNOWN profiles qualify, so a target
     * that is genuinely down stays DOWN and is not re-probed here.
     */
    private void probeUncontacted(List<ConnectionProfile> profiles) {
        profiles.stream()
                .filter(profile -> registry.status(profile.id).state() == ConnectionState.UNKNOWN)
                .forEach(this::probeInBackground);
    }

    /**
     * Starts a probe and returns immediately.
     *
     * <p>The caller's response must not wait on a network round trip, and the outcome does not need
     * to be returned: {@link ConnectionRegistry#refresh} records it and the notification hub pushes
     * the change, so the UI updates itself.
     */
    private void probeInBackground(ConnectionProfile profile) {
        registry.refresh(profile)
                .subscribe()
                .with(
                        ignored -> {},
                        failure ->
                                LOG.debugf(
                                        failure, "Background probe failed for %s", profile.name));
    }

    @WithSession
    public Uni<ConnectionResponse> get(Long id) {
        return require(id).map(profile -> mapper.toResponse(profile, registry.status(id)));
    }

    @WithTransaction
    public Uni<ConnectionResponse> create(ConnectionRequest request) {
        return requireNameAvailable(request.name(), null)
                .flatMap(
                        ignored -> {
                            ConnectionProfile profile = new ConnectionProfile();
                            mapper.apply(request, profile);
                            requireEngineIsBuiltIn(profile);
                            requireUsableCertificates(profile);
                            CommandPolicy.requireAskable(profile);
                            return repository.persist(profile);
                        })
                .invoke(
                        profile -> {
                            // The id alone rather than the whole row. Every listener invalidates
                            // and asks again — filtered, as that list always is — so sending the
                            // name, host and port of a target to browsers that may not see it
                            // would be giving away the thing the refetch is careful about.
                            //
                            // Untagged, like the other two lifecycle events. Tagging it would
                            // filter it against audiences worked out before this target existed,
                            // so nobody with a page already open would ever hear of it. The
                            // audiences are worked out again just below, which is the fix rather
                            // than the tag; an id that has started existing is not something
                            // worth withholding on its own.
                            hub.broadcast(
                                    NotificationCategory.CONNECTION_CREATED,
                                    Map.of("id", profile.id));
                            audience.refreshAll();
                            // Probe immediately so a new row does not sit at "unknown" until
                            // someone tests it by hand; the result arrives over the hub.
                            probeInBackground(profile);
                        })
                .map(profile -> mapper.toResponse(profile, ConnectionStatus.unknown()));
    }

    @WithTransaction
    public Uni<ConnectionResponse> update(Long id, ConnectionRequest request) {
        return require(id)
                .flatMap(
                        profile ->
                                requireNameAvailable(request.name(), id)
                                        .map(
                                                ignored -> {
                                                    mapper.apply(request, profile);
                                                    requireEngineIsBuiltIn(profile);
                                                    requireUsableCertificates(profile);
                                                    CommandPolicy.requireAskable(profile);
                                                    return profile;
                                                }))
                .invoke(
                        profile -> {
                            // Host, port, credentials or TLS may have changed, so the cached
                            // client points at the wrong target; drop it and redial, otherwise
                            // the row keeps reporting the previous target's health.
                            registry.invalidate(id);
                            probeInBackground(profile);
                        })
                .map(profile -> mapper.toResponse(profile, registry.status(id)))
                .invoke(
                        response ->
                                hub.broadcast(
                                        NotificationCategory.CONNECTION_UPDATED, Map.of("id", id)));
    }

    @WithTransaction
    public Uni<Void> delete(Long id) {
        return require(id)
                .flatMap(repository::delete)
                .invoke(
                        () -> {
                            registry.close(id);
                            // Everything else holding something for this target — the sampler's
                            // timer, the readings it kept — lets go of it here.
                            removed.fire(new ConnectionRemoved(id));
                            // Untagged, and here it could not be otherwise: whether somebody may
                            // see a target is answered by looking the target up, and this one has
                            // just been deleted. Tagging it would filter it out for everybody,
                            // including whoever deleted it, and their list would keep showing a
                            // row that is gone.
                            hub.broadcast(
                                    NotificationCategory.CONNECTION_DELETED, Map.of("id", id));
                            audience.refreshAll();
                        });
    }

    /**
     * Probes a saved profile and records the outcome.
     *
     * <p>The lookup runs in a read-only session that closes before the probe begins, so no database
     * connection is pinned for the length of a network round trip.
     */
    public Uni<ConnectionStatus> test(Long id) {
        return load(id).flatMap(registry::refresh);
    }

    /**
     * Probes a profile as a form currently describes it, saved or not.
     *
     * <p>Nothing is recorded: the registry's own probe is side-effect free precisely so it can
     * serve a profile that does not exist yet, and a target's tracked status is not something a
     * form somebody is still filling in gets to change.
     *
     * @param id the profile being edited, or null for a new one — an edit sends back no secret it
     *     did not change, so the stored password, client key and key passphrase are what the
     *     attempt uses
     */
    public Uni<ConnectionStatus> testDraft(Long id, ConnectionRequest request) {
        Uni<ConnectionProfile> stored = id == null ? Uni.createFrom().nullItem() : load(id);
        return stored.map(existing -> draft(existing, request)).flatMap(registry::test);
    }

    /**
     * A profile as the form describes it, detached from any session.
     *
     * <p>Copied rather than edited in place: applying the request to the entity the session
     * returned would be an edit, and an edit inside a session is a write waiting for a flush.
     */
    private ConnectionProfile draft(ConnectionProfile stored, ConnectionRequest request) {
        ConnectionProfile draft = new ConnectionProfile();
        if (stored != null) {
            draft.id = stored.id;
            draft.name = stored.name;
            draft.host = stored.host;
            draft.port = stored.port;
            draft.username = stored.username;
            draft.password = stored.password;
            draft.tls = stored.tls;
            // The write-only halves, for the reason the password is here: an edit sends back
            // nothing it did not change, so a form testing a saved profile would otherwise be
            // testing it without the key it actually connects with — a certificate presented with
            // no private half, which fails in a way that reads like the target refusing it.
            draft.tlsCaCert = stored.tlsCaCert;
            draft.tlsClientCert = stored.tlsClientCert;
            draft.tlsClientKey = stored.tlsClientKey;
            draft.tlsClientKeyPassphrase = stored.tlsClientKeyPassphrase;
            draft.database = stored.database;
            draft.engine = stored.engine;
            draft.flavor = stored.flavor;
            draft.type = stored.type;
            draft.sentinelMasterName = stored.sentinelMasterName;
            draft.notes = stored.notes;
            draft.tunnelId = stored.tunnelId;
        }
        mapper.apply(request, draft);
        return draft;
    }

    /**
     * Loads a profile for another service to act on.
     *
     * <p>Public because sibling domains (key browsing, the console) operate against a connection
     * and need its target details. It returns the entity rather than a DTO on purpose: this is a
     * service-to-service call, and the entity never reaches the transport.
     */
    @WithSession
    public Uni<ConnectionProfile> load(Long id) {
        return require(id);
    }

    /**
     * The profile, pointed at one of its other databases for the length of this request.
     *
     * <p>Set on the loaded instance rather than on a copy: the field is transient, so nothing is
     * written back, and each request has its own session and its own instance of the entity. A null
     * database means the profile's own, which is what every caller that does not care sends.
     */
    @WithSession
    public Uni<ConnectionProfile> load(Long id, Integer database) {
        return require(id)
                .invoke(profile -> profile.selectedDatabase = database)
                .invoke(ConnectionService::rejectOutOfRange);
    }

    /**
     * Certificates are read when the profile is saved, not when it is dialled.
     *
     * <p>A certificate that does not parse is otherwise a connection that fails at three in the
     * morning with a message about handshakes — which names neither the profile nor the field. The
     * same reasoning the destination check follows: the moment to find out is while somebody is
     * looking at the form.
     *
     * <p>Half of a pair is refused for the same reason. A client certificate with no key, or a key
     * with no certificate, is a thing that can only fail later and can only fail obscurely. So is a
     * key whose passphrase is missing, wrong, or supplied for a key that was never locked — three
     * different mistakes with three different things to do about them, and one indistinguishable
     * handshake failure if nobody separates them here.
     *
     * <p>And only where a certificate can actually be presented. An engine that ignored these
     * fields would be configuration that looks applied and does nothing, which is worse than not
     * offering it — which is why TiKV, whose client wants files on disk, is refused rather than
     * quietly given something it will not read.
     */
    /**
     * Refuses a target this build cannot serve, while somebody is still looking at the form.
     *
     * <p>TiKV is behind a Maven profile, so an image can be running that has no engine for it. Left
     * to itself the profile would save, sit in the list looking like every other one, and fail at
     * the first request with something about a missing bean — which is a bug report rather than an
     * answer. The moment to say so is the moment somebody asks for it.
     */
    private void requireEngineIsBuiltIn(ConnectionProfile profile) {
        if (!engines.has(profile.engine)) {
            throw new InvalidConnectionException(
                    "This installation cannot manage a "
                            + profile.engine
                            + " target: the engine is not in this build of Keydra. An image built"
                            + " with the `tikv` profile can, and the published one is not.");
        }
    }

    private static void requireUsableCertificates(ConnectionProfile profile) {
        boolean any =
                Certificates.present(profile.tlsCaCert)
                        || Certificates.present(profile.tlsClientCert)
                        || Certificates.present(profile.tlsClientKey)
                        || Certificates.present(profile.tlsClientKeyPassphrase);
        if (!any) {
            return;
        }
        // TiKV alone still refuses them: its client reads certificates from files on disk rather
        // than from material held in memory, which is a different piece of work and not one to
        // fake with temporary files.
        if (profile.engine == EngineType.TIKV) {
            throw new InvalidConnectionException(
                    "Certificates cannot be presented to a TiKV target: its client reads them from"
                            + " files on disk rather than from here.");
        }
        if (!profile.tls) {
            throw new InvalidConnectionException(
                    "Certificates apply to a TLS connection, and TLS is off for this target.");
        }
        if (Certificates.present(profile.tlsCaCert)) {
            Certificates.requireCertificate(profile.tlsCaCert, "The certificate authority");
        }
        if (Certificates.present(profile.tlsClientCert)
                != Certificates.present(profile.tlsClientKey)) {
            throw new InvalidConnectionException(
                    "A client certificate and its private key go together. Supply both, or"
                            + " neither.");
        }
        if (Certificates.present(profile.tlsClientCert)) {
            Certificates.requireCertificate(profile.tlsClientCert, "The client certificate");
            Certificates.requirePrivateKey(
                    profile.tlsClientKey, profile.tlsClientKeyPassphrase, "The client key");
        } else if (Certificates.present(profile.tlsClientKeyPassphrase)) {
            // A passphrase with nothing to open. The pair check above lets this through because
            // it is about the certificate and the key; left alone it would be a secret stored
            // for a key that is not there, which nothing will ever read and nobody will ever see.
            throw new InvalidConnectionException(
                    "A key passphrase applies to a client key, and there is no client key on this"
                            + " target.");
        }
    }

    /**
     * A database index has to be a database.
     *
     * <p>Checked here rather than left to the server: a connection string with a nonsensical index
     * fails at connect time, which arrives looking like the target being down.
     */
    private static void rejectOutOfRange(ConnectionProfile profile) {
        if (profile.selectedDatabase != null && profile.selectedDatabase < 0) {
            throw new InvalidConnectionException(
                    "A database index cannot be negative: " + profile.selectedDatabase);
        }
    }

    private Uni<ConnectionProfile> require(Long id) {
        return repository
                .findById(id)
                .onItem()
                .ifNull()
                .failWith(() -> new ConnectionNotFoundException(id));
    }

    /** Names are the human handle for a target, so they stay unique across profiles. */
    private Uni<Void> requireNameAvailable(String name, Long selfId) {
        return repository
                .findByName(name)
                .flatMap(
                        existing -> {
                            if (existing != null && !existing.id.equals(selfId)) {
                                return Uni.createFrom()
                                        .failure(new DuplicateConnectionNameException(name));
                            }
                            return Uni.createFrom().voidItem();
                        });
    }
}
