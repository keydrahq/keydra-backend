package io.keydra.tunnels.service;

import io.keydra.tunnels.dto.TunnelDtos.TunnelCheck;
import io.keydra.tunnels.dto.TunnelDtos.TunnelRequest;
import io.keydra.tunnels.dto.TunnelDtos.TunnelSummary;
import io.keydra.tunnels.entity.SshTunnel;
import io.keydra.tunnels.exception.TunnelConflictException;
import io.keydra.tunnels.mapper.TunnelMapper;
import io.keydra.tunnels.persistence.TunnelRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;

/**
 * The jump hosts, as rows somebody edits.
 *
 * <p>Configured once and pointed at by anything that has to reach past them. Which is the same move
 * backup destinations made, for the same reason: a credential should live in one place, so rotating
 * it is one edit.
 */
@ApplicationScoped
public class TunnelService {

    private final TunnelRepository repository;
    private final TunnelMapper mapper;
    private final TunnelManager tunnels;

    @Inject
    TunnelService(TunnelRepository repository, TunnelMapper mapper, TunnelManager tunnels) {
        this.repository = repository;
        this.mapper = mapper;
        this.tunnels = tunnels;
    }

    @WithSession
    public Uni<List<TunnelSummary>> list() {
        return repository.all().flatMap(this::describe);
    }

    /**
     * Every tunnel with what points at it.
     *
     * <p>Counted one after another rather than at once: each is a small query on the same reactive
     * session, and a session is single-threaded — issuing them concurrently is how you get "session
     * is currently executing another query" instead of a faster answer.
     */
    private Uni<List<TunnelSummary>> describe(List<SshTunnel> found) {
        Uni<List<TunnelSummary>> described = Uni.createFrom().item(new ArrayList<>());
        for (SshTunnel tunnel : found) {
            described =
                    described.flatMap(
                            soFar ->
                                    repository
                                            .usageOf(tunnel.id)
                                            .map(
                                                    usedBy -> {
                                                        soFar.add(mapper.toSummary(tunnel, usedBy));
                                                        return soFar;
                                                    }));
        }
        return described.map(List::copyOf);
    }

    @WithTransaction
    public Uni<TunnelSummary> create(TunnelRequest request) {
        return repository
                .byName(request.name())
                .flatMap(
                        existing -> {
                            if (existing != null) {
                                return Uni.createFrom()
                                        .failure(
                                                new TunnelConflictException(
                                                        "A tunnel called "
                                                                + request.name()
                                                                + " already exists"));
                            }
                            SshTunnel tunnel = new SshTunnel();
                            mapper.apply(request, tunnel);
                            requireACredential(tunnel);
                            return repository.save(tunnel).map(saved -> mapper.toSummary(saved, 0));
                        });
    }

    @WithTransaction
    public Uni<TunnelSummary> update(Long id, TunnelRequest request) {
        return repository
                .byId(id)
                .flatMap(
                        tunnel -> {
                            if (tunnel == null) {
                                return Uni.createFrom()
                                        .failure(new TunnelConflictException("No such tunnel"));
                            }
                            mapper.apply(request, tunnel);
                            requireACredential(tunnel);
                            // The open session was built from what this row used to say, and
                            // the usual reason somebody edits a jump host is that it changed.
                            // Everything behind it reconnects through the new one.
                            tunnels.close(id);
                            return repository
                                    .usageOf(id)
                                    .map(usedBy -> mapper.toSummary(tunnel, usedBy));
                        });
    }

    /**
     * Removes a tunnel, refusing while anything still reaches through it.
     *
     * <p>The database would set those references to null and leave the targets trying to connect
     * directly to an address that is not reachable — a failure that looks like the server being
     * down rather than like a tunnel that was deleted.
     */
    @WithTransaction
    public Uni<Boolean> delete(Long id) {
        return repository
                .usageOf(id)
                .flatMap(
                        usedBy -> {
                            if (usedBy > 0) {
                                return Uni.createFrom()
                                        .failure(
                                                new TunnelConflictException(
                                                        usedBy
                                                                + " targets or destinations are"
                                                                + " reached through this tunnel."
                                                                + " Point them somewhere else"
                                                                + " first."));
                            }
                            tunnels.close(id);
                            return repository.delete(id);
                        });
    }

    /**
     * Whether the jump host answers and lets us in.
     *
     * <p>Answers the fingerprint it presented whether or not the attempt worked, because that is
     * the thing somebody came here to find out: a tunnel that pins no key is one anything can
     * impersonate, and pinning one should be a copy and a save rather than a trip to a terminal.
     *
     * <p>Never fails the request. "It did not work, and here is what it said" is the answer being
     * asked for.
     */
    public Uni<TunnelCheck> check(Long id) {
        return repository
                .forUse(id)
                .onItem()
                .ifNull()
                .failWith(() -> new TunnelConflictException("No such tunnel"))
                .flatMap(this::attempt);
    }

    /**
     * Tries a jump host described in a request rather than one already stored.
     *
     * <p>What "test connection" is for a target, and here for the same reason: the moment to find
     * out that a key is wrong is while somebody is looking at the form, not the next time something
     * behind the jump host is needed. Nothing is written — the draft is a detached copy, so the
     * session this reads through cannot flush an edit somebody has not saved.
     *
     * @param id the tunnel being edited, or null for one being written for the first time; an edit
     *     sends no secret it did not change, so the stored ones are what the attempt uses
     */
    public Uni<TunnelCheck> check(Long id, TunnelRequest request) {
        Uni<SshTunnel> stored = id == null ? Uni.createFrom().nullItem() : repository.forUse(id);
        return stored.map(existing -> draft(existing, request)).flatMap(this::attempt);
    }

    /**
     * A tunnel as the form currently describes it, detached from any session.
     *
     * <p>Copied field by field rather than edited in place. Applying the request to the entity the
     * session returned would be an edit, and an edit inside a session is a write waiting for a
     * flush — of a form somebody may still be filling in.
     */
    private SshTunnel draft(SshTunnel stored, TunnelRequest request) {
        SshTunnel draft = new SshTunnel();
        if (stored != null) {
            draft.name = stored.name;
            draft.host = stored.host;
            draft.port = stored.port;
            draft.username = stored.username;
            draft.password = stored.password;
            draft.privateKey = stored.privateKey;
            draft.passphrase = stored.passphrase;
            draft.hostKeyFingerprint = stored.hostKeyFingerprint;
        }
        mapper.apply(request, draft);
        requireACredential(draft);
        return draft;
    }

    private Uni<TunnelCheck> attempt(SshTunnel tunnel) {
        return tunnels.check(tunnel)
                .map(
                        fingerprint ->
                                new TunnelCheck(true, "Connected and authenticated", fingerprint))
                .onFailure()
                .recoverWithItem(failure -> new TunnelCheck(false, failure.getMessage(), null));
    }

    /** A jump host with neither is one nothing can log in to, refused where somebody can see it. */
    private static void requireACredential(SshTunnel tunnel) {
        if (!tunnel.hasPassword() && !tunnel.hasPrivateKey()) {
            throw new TunnelConflictException("A tunnel needs either a password or a private key");
        }
    }
}
