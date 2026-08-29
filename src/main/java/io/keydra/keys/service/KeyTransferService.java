package io.keydra.keys.service;

import io.keydra.approvals.entity.ApprovalKind;
import io.keydra.approvals.service.Approvals;
import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.service.ConnectionService;
import io.keydra.connections.service.GuardedTargets;
import io.keydra.engine.EngineSelector;
import io.keydra.engine.KeyQuery;
import io.keydra.engine.KeyTransfer;
import io.keydra.engine.RestoreOutcome;
import io.keydra.engine.SerializedKey;
import io.keydra.events.dto.NotificationCategory;
import io.keydra.events.service.NotificationHub;
import io.keydra.keys.approval.KeyApprovalPayloads.ImportKeysPayload;
import io.keydra.keys.dto.ExportKeysRequest;
import io.keydra.keys.dto.ExportedKey;
import io.keydra.keys.dto.ImportKeysRequest;
import io.keydra.keys.dto.ImportResult;
import io.keydra.keys.exception.TransferUnsupportedException;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Taking keys out of a target and putting them back.
 *
 * <p>Speaks only to the engine's {@link KeyTransfer} capability, so a store that cannot serialise a
 * value is refused here once rather than failing somewhere inside a half-finished export.
 */
@ApplicationScoped
public class KeyTransferService {

    private final ConnectionService connections;
    private final EngineSelector engines;
    private final NotificationHub hub;
    private final Approvals approvals;
    private final int batchSize;

    @Inject
    KeyTransferService(
            ConnectionService connections,
            EngineSelector engines,
            NotificationHub hub,
            Approvals approvals,
            @ConfigProperty(name = "keydra.keys.transfer-batch-size", defaultValue = "200")
                    int batchSize) {
        this.connections = connections;
        this.engines = engines;
        this.hub = hub;
        this.approvals = approvals;
        this.batchSize = batchSize;
    }

    /**
     * Streams the requested keys as export entries.
     *
     * <p>A stream rather than a list: an export reads every value, and buffering the whole result
     * would hold the keyspace in the server's memory before the first byte reached the caller.
     *
     * <p>Keys that have expired since they were named drop out silently. Asking for a key that is
     * gone is not an error in an export; it is the reason the file is being taken.
     */
    public Multi<ExportedKey> export(Long connectionId, ExportKeysRequest request) {
        return connections
                .load(connectionId)
                .onItem()
                .transformToMulti(
                        profile ->
                                inBatches(names(profile, request))
                                        .onItem()
                                        .transformToUniAndConcatenate(
                                                batch -> transfer(profile).dumpMany(profile, batch))
                                        .onItem()
                                        // Mutiny cannot infer the element type of a flattened
                                        // list, so it is named.
                                        .<SerializedKey>disjoint()
                                        .map(KeyTransferService::toDto));
    }

    /**
     * Restores keys, reporting what happened to each.
     *
     * <p>One key's failure does not stop the rest: a file is usually restored because something has
     * gone wrong already, and an import that stops at the first unreadable entry leaves the store
     * half-populated with no way to tell how far it got.
     */
    public Uni<ImportResult> importKeys(Long connectionId, ImportKeysRequest request) {
        return connections
                .load(connectionId)
                .flatMap(
                        profile -> {
                            GuardedTargets.requireNamed(
                                    profile,
                                    request.confirmTarget(),
                                    "This would write "
                                            + request.keys().size()
                                            + " keys into it"
                                            + (request.replace()
                                                    ? ", replacing what is there"
                                                    : ""));
                            // The second question, in the same place as the first. Writing a dump
                            // over a keyspace is the operation with the least in common with
                            // "restoring a backup": nothing says the file matches this server.
                            return approvals.require(
                                    profile,
                                    null,
                                    ApprovalKind.IMPORT_KEYS,
                                    new ImportKeysPayload(request));
                        })
                .flatMap(
                        ignored ->
                                importStream(
                                        connectionId,
                                        Multi.createFrom().iterable(request.keys()),
                                        request.replace()));
    }

    /**
     * The same restore, fed by a stream rather than by a list.
     *
     * <p>What a restore from a backup file uses. The list form exists because an API request
     * carries one; a file does not have to be turned into one first, and turning a large one into a
     * list would put the whole backup in memory to avoid writing a second method.
     */
    public Uni<ImportResult> importStream(
            Long connectionId, Multi<ExportedKey> keys, boolean replace) {
        return connections
                .load(connectionId)
                .flatMap(
                        profile ->
                                keys.map(KeyTransferService::toEngine)
                                        .group()
                                        .intoLists()
                                        .of(batchSize)
                                        .onItem()
                                        .transformToUniAndConcatenate(
                                                batch ->
                                                        transfer(profile)
                                                                .restoreMany(
                                                                        profile, batch, replace))
                                        .onItem()
                                        .<RestoreOutcome>disjoint()
                                        .collect()
                                        .in(Tally::new, Tally::add)
                                        .map(Tally::result))
                .invoke(result -> announce(connectionId, result));
    }

    /** Groups a stream of key names into batches the size of one round trip. */
    Multi<List<String>> inBatches(Multi<String> keys) {
        return keys.group().intoLists().of(batchSize);
    }

    /** The keys an export was asked for: the names it was given, or what a glob finds. */
    Multi<String> names(ConnectionProfile profile, ExportKeysRequest request) {
        return names(profile, request, null);
    }

    /**
     * The same, narrowed to one type.
     *
     * <p>Narrowed at the walk rather than after it, so a key of another type is never read. That
     * matters more than it sounds: the alternative reads every key to find out what it is, which on
     * a keyspace where the wanted type is a tenth of it is ten times the round trips for the same
     * answer.
     *
     * @param type what the store calls the type — {@code hash}, {@code stream} — or null for all
     */
    Multi<String> names(ConnectionProfile profile, ExportKeysRequest request, String type) {
        if (request.keys() != null && !request.keys().isEmpty()) {
            return Multi.createFrom().iterable(request.keys());
        }
        // Names only: the export reads each value with its own command, so the scan has no
        // use for the TTL that a full walk would fetch per key.
        return engines.forProfile(profile)
                .scanKeyNames(profile, new KeyQuery(request.match(), KeyQuery.DEFAULT_COUNT, type))
                .select()
                .first(request.limitOrDefault());
    }

    KeyTransfer transfer(ConnectionProfile profile) {
        return engines.forProfile(profile)
                .transfer()
                .orElseThrow(() -> new TransferUnsupportedException(profile.engine.name()));
    }

    private static ExportedKey toDto(SerializedKey key) {
        return new ExportedKey(key.key(), key.ttlMillis(), key.payload());
    }

    private static SerializedKey toEngine(ExportedKey key) {
        return new SerializedKey(key.key(), key.ttlMillis(), key.payload());
    }

    /** An import writes keys, so other viewers of the same target refresh themselves. */
    private void announce(Long connectionId, ImportResult result) {
        hub.broadcast(
                NotificationCategory.KEYS_CHANGED,
                connectionId,
                Map.of(
                        "connectionId",
                        connectionId,
                        "operation",
                        "imported",
                        "requested",
                        result.restored() + result.skipped() + result.failed(),
                        "affected",
                        result.restored()));
    }

    /**
     * Counts outcomes as the work runs, so nothing is held but three numbers and one message.
     *
     * <p>The first refusal's reason, not the last: they are nearly always the same failure repeated
     * once per key, and the first is the one that explains the file.
     */
    static final class Tally {
        private long restored;
        private long skipped;
        private long failed;
        private String reason;

        void add(RestoreOutcome outcome) {
            if (outcome.written()) {
                restored++;
            } else if (outcome.isFailure()) {
                failed++;
                if (reason == null) {
                    reason = outcome.refusal();
                }
            } else {
                skipped++;
            }
        }

        ImportResult result() {
            return new ImportResult(restored, skipped, failed, reason);
        }
    }
}
