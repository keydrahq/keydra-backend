package io.keydra.keys.service;

import io.keydra.approvals.entity.ApprovalKind;
import io.keydra.approvals.service.Approvals;
import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.service.ConnectionService;
import io.keydra.connections.service.GuardedTargets;
import io.keydra.engine.EngineSelector;
import io.keydra.engine.KeyQuery;
import io.keydra.engine.KeyValueEngine;
import io.keydra.events.dto.NotificationCategory;
import io.keydra.events.service.NotificationHub;
import io.keydra.keys.approval.KeyApprovalPayloads.DeleteKeysPayload;
import io.keydra.keys.approval.KeyApprovalPayloads.PurgeKeysPayload;
import io.keydra.keys.dto.CopyKeyRequest;
import io.keydra.keys.dto.ExpireKeyRequest;
import io.keydra.keys.dto.KeyEntry;
import io.keydra.keys.dto.KeyOperationResult;
import io.keydra.keys.dto.NamespaceNode;
import io.keydra.keys.dto.PurgeKeysRequest;
import io.keydra.keys.dto.RenameKeyRequest;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Key browsing and mutation for one connection.
 *
 * <p>Speaks only to {@link KeyValueEngine}, so nothing here knows which protocol the target uses
 * and a new backing store needs no change in this class.
 */
@ApplicationScoped
public class KeyService {

    private final ConnectionService connections;
    private final EngineSelector engines;
    private final NamespaceTreeBuilder treeBuilder;
    private final NotificationHub hub;
    private final Approvals approvals;
    private final int treeSampleLimit;
    private final int purgeBatchSize;

    @Inject
    KeyService(
            ConnectionService connections,
            EngineSelector engines,
            NamespaceTreeBuilder treeBuilder,
            NotificationHub hub,
            Approvals approvals,
            @ConfigProperty(name = "keydra.keys.tree-sample-limit", defaultValue = "10000")
                    int treeSampleLimit,
            @ConfigProperty(name = "keydra.keys.purge-batch-size", defaultValue = "500")
                    int purgeBatchSize) {
        this.connections = connections;
        this.engines = engines;
        this.treeBuilder = treeBuilder;
        this.hub = hub;
        this.approvals = approvals;
        this.treeSampleLimit = treeSampleLimit;
        this.purgeBatchSize = purgeBatchSize;
    }

    public Multi<KeyEntry> scan(Long connectionId, Integer database, KeyQuery query) {
        return connections
                .load(connectionId, database)
                .onItem()
                .transformToMulti(profile -> engine(profile).scanKeys(profile, query));
    }

    /**
     * Immediate children of a namespace prefix.
     *
     * <p>Bounded by a sample: the tree is a navigation aid, and walking a million keys to draw one
     * level would defeat the point. {@link NamespaceNode} documents that its counts are sampled.
     */
    public Uni<List<NamespaceNode>> tree(
            Long connectionId, Integer database, String prefix, String delimiter, int count) {
        String safePrefix = prefix == null ? "" : prefix;
        String safeDelimiter = delimiter == null || delimiter.isEmpty() ? ":" : delimiter;

        return connections
                .load(connectionId, database)
                .flatMap(
                        profile ->
                                engine(profile)
                                        // Names only: the tree groups by delimiter and counts,
                                        // and never looks at a key's type or TTL.
                                        .scanKeyNames(
                                                profile,
                                                new KeyQuery(safePrefix + "*", count, null))
                                        .select()
                                        // One more than the limit, so "we stopped early" can be
                                        // told from "that was all of them". Asking for exactly the
                                        // limit makes a keyspace of precisely ten thousand keys
                                        // indistinguishable from one of ten million.
                                        .first(treeSampleLimit + 1)
                                        .collect()
                                        .asList())
                .map(
                        keys -> {
                            boolean partial = keys.size() > treeSampleLimit;
                            List<String> sample = partial ? keys.subList(0, treeSampleLimit) : keys;
                            return treeBuilder.children(sample, safePrefix, safeDelimiter, partial);
                        });
    }

    public Uni<KeyOperationResult> delete(
            Long connectionId, Integer database, List<String> keys, String confirmTarget) {
        return connections
                .load(connectionId, database)
                .flatMap(
                        profile -> {
                            // Where the profile is in hand, because the guard is a fact about the
                            // target rather than about the request — and a check in the resource
                            // would be a check the scheduled work and the GraphQL surface skip.
                            GuardedTargets.requireNamed(
                                    profile,
                                    confirmTarget,
                                    "This would delete "
                                            + keys.size()
                                            + (keys.size() == 1 ? " key" : " keys"));
                            // The second question, asked in the same place and about the same
                            // target: naming it says which server this is, and this says whether
                            // one person gets to do it on their own.
                            return approvals
                                    .require(
                                            profile,
                                            null,
                                            ApprovalKind.DELETE_KEYS,
                                            new DeleteKeysPayload(database, keys))
                                    .flatMap(ignored -> removeKeys(profile, connectionId, keys));
                        });
    }

    /**
     * The same delete, on a request a second person has already agreed to.
     *
     * <p>Called by nothing but the approvals runner. It skips only the asking: what it deletes is
     * what was written down when the request was raised, which is the whole reason the request
     * holds the operation rather than an unlock somebody re-uses.
     */
    public Uni<KeyOperationResult> deleteApproved(
            Long connectionId, Integer database, List<String> keys) {
        return connections
                .load(connectionId, database)
                .flatMap(profile -> removeKeys(profile, connectionId, keys));
    }

    private Uni<KeyOperationResult> removeKeys(
            ConnectionProfile profile, Long connectionId, List<String> keys) {
        return engine(profile)
                .deleteKeys(profile, keys)
                .map(KeyOperationResult::new)
                .invoke(
                        result ->
                                announce(connectionId, "deleted", keys.size(), result.affected()));
    }

    /**
     * Deletes everything a pattern matches.
     *
     * <p>Walked with the cursor and deleted a batch at a time, rather than named key by key from
     * the browser: clearing a namespace of five thousand keys should not mean sending five thousand
     * names back to the server that already knows them, and the walk never holds more than one
     * batch.
     *
     * <p>The count is of keys the store actually removed, which can be lower than the number found:
     * a key may expire between being walked past and being deleted, and that is the ordinary case
     * rather than a fault.
     */
    public Uni<KeyOperationResult> purge(
            Long connectionId, Integer database, PurgeKeysRequest request) {
        return connections
                .load(connectionId, database)
                .flatMap(
                        profile -> {
                            GuardedTargets.requireNamed(
                                    profile,
                                    request.confirmTarget(),
                                    "This would delete everything matching " + request.match());
                            return approvals
                                    .require(
                                            profile,
                                            null,
                                            ApprovalKind.PURGE_KEYS,
                                            new PurgeKeysPayload(database, request))
                                    .flatMap(
                                            ignored ->
                                                    clearMatching(profile, connectionId, request));
                        });
    }

    /**
     * The same purge, on a request a second person has already agreed to.
     *
     * <p>The glob is the one that was written down when the request was raised. That is the point
     * of storing the operation rather than issuing an unlock: between an agreement and a second
     * request a glob can widen, and nothing in the record would show it.
     */
    public Uni<KeyOperationResult> purgeApproved(
            Long connectionId, Integer database, PurgeKeysRequest request) {
        return connections
                .load(connectionId, database)
                .flatMap(profile -> clearMatching(profile, connectionId, request));
    }

    private Uni<KeyOperationResult> clearMatching(
            ConnectionProfile profile, Long connectionId, PurgeKeysRequest request) {
        java.util.concurrent.atomic.AtomicLong progress =
                new java.util.concurrent.atomic.AtomicLong();
        return engine(profile)
                .scanKeyNames(profile, new KeyQuery(request.match(), KeyQuery.DEFAULT_COUNT, null))
                .select()
                .first(request.limitOrDefault())
                .group()
                .intoLists()
                .of(purgeBatchSize)
                .onItem()
                .transformToUniAndConcatenate(
                        batch ->
                                engine(profile)
                                        .deleteKeys(profile, batch)
                                        // Said per batch rather than at
                                        // the end. A purge of a large
                                        // keyspace is a minute of a
                                        // dialog saying nothing, and the
                                        // socket that carries a
                                        // migration's progress is
                                        // already open.
                                        .invoke(
                                                removed ->
                                                        announceProgress(
                                                                connectionId,
                                                                request.match(),
                                                                progress.addAndGet(removed))))
                .collect()
                .in(
                        java.util.concurrent.atomic.AtomicLong::new,
                        java.util.concurrent.atomic.AtomicLong::addAndGet)
                .map(java.util.concurrent.atomic.AtomicLong::get)
                .map(KeyOperationResult::new)
                .invoke(
                        result ->
                                announce(
                                        connectionId,
                                        "purged",
                                        (int) result.affected(),
                                        result.affected()));
    }

    /**
     * How many a purge has removed so far.
     *
     * <p>Tagged with the target, so it reaches the people who may see that target and nobody else —
     * the pattern being cleared is as much a description of somebody's keyspace as the keys are.
     */
    private void announceProgress(Long connectionId, String match, long deleted) {
        hub.broadcast(
                NotificationCategory.PURGE_PROGRESS,
                connectionId,
                java.util.Map.of(
                        "connectionId",
                        connectionId,
                        "match",
                        match == null ? "" : match,
                        "deleted",
                        deleted));
    }

    public Uni<KeyOperationResult> rename(
            Long connectionId, Integer database, RenameKeyRequest request) {
        return connections
                .load(connectionId, database)
                .flatMap(
                        profile ->
                                engine(profile)
                                        .renameKey(
                                                profile,
                                                request.from(),
                                                request.to(),
                                                request.replace()))
                .map(KeyOperationResult::new)
                .invoke(result -> announce(connectionId, "renamed", 1, result.affected()));
    }

    public Uni<KeyOperationResult> copy(
            Long connectionId, Integer database, CopyKeyRequest request) {
        return connections
                .load(connectionId, database)
                .flatMap(
                        profile ->
                                engine(profile)
                                        .copyKey(
                                                profile,
                                                request.from(),
                                                request.to(),
                                                request.replace()))
                .map(KeyOperationResult::new)
                .invoke(result -> announce(connectionId, "copied", 1, result.affected()));
    }

    /** Sets a TTL, or removes it when the request carries none. */
    public Uni<KeyOperationResult> expire(
            Long connectionId, Integer database, ExpireKeyRequest request) {
        return connections
                .load(connectionId, database)
                .flatMap(
                        profile ->
                                engine(profile)
                                        .setExpiry(profile, request.key(), request.ttlSeconds()))
                .map(KeyOperationResult::new)
                .invoke(result -> announce(connectionId, "expiry-changed", 1, result.affected()));
    }

    private KeyValueEngine engine(ConnectionProfile profile) {
        return engines.forProfile(profile);
    }

    /** Key mutations are broadcast so other viewers of the same target refresh themselves. */
    private void announce(Long connectionId, String operation, int requested, long affected) {
        hub.broadcast(
                NotificationCategory.KEYS_CHANGED,
                connectionId,
                Map.of(
                        "connectionId", connectionId,
                        "operation", operation,
                        "requested", requested,
                        "affected", affected));
    }
}
