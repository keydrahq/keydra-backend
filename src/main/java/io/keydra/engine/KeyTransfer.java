package io.keydra.engine;

import io.keydra.connections.entity.ConnectionProfile;
import io.smallrye.mutiny.Uni;
import java.util.List;

/**
 * Taking keys out of a store and putting them back.
 *
 * <p>Optional on {@link KeyValueEngine} like the console and the message bus: a store that cannot
 * hand back a value as bytes says so with an empty Optional rather than by throwing at the first
 * call.
 *
 * <p>Batches, not whole keyspaces. A "dump everything" method would have to decide how to walk it,
 * how much to hold in memory and what to do when one key fails halfway; the caller already has a
 * cursor-based scan and can decide all three. A batch, on the other hand, is what turns one round
 * trip per key into one per hundred — which is the difference between migrating a large keyspace
 * and crawling it.
 */
public interface KeyTransfer {

    /**
     * Serialises a batch of keys.
     *
     * <p>Keys that no longer exist are simply absent from the answer, which is not an error: a key
     * listed a moment ago may have expired since, and that is the ordinary case rather than a
     * fault. The answer is therefore not necessarily the same length as the request.
     */
    Uni<List<SerializedKey>> dumpMany(ConnectionProfile profile, List<String> keys);

    /**
     * Restores a batch of serialised keys, saying what happened to each.
     *
     * @param replace whether to overwrite keys that already exist; without it an existing key is
     *     left alone, so a restore cannot quietly destroy data
     */
    Uni<List<RestoreOutcome>> restoreMany(
            ConnectionProfile profile, List<SerializedKey> keys, boolean replace);

    /**
     * Reads a batch of keys as values rather than as serialised bytes.
     *
     * <p>The slow way, and the one that always works. See {@link CopiedKey} for when it is needed.
     * As with {@link #dumpMany}, a key that has gone is simply absent from the answer.
     */
    Uni<List<CopiedKey>> readMany(ConnectionProfile profile, List<String> keys);

    /**
     * Writes back what {@link #readMany} produced, saying what happened to each key.
     *
     * @param replace whether to overwrite keys that already exist; without it an existing key is
     *     left alone, so a copy cannot quietly destroy data
     */
    Uni<List<RestoreOutcome>> writeMany(
            ConnectionProfile profile, List<CopiedKey> keys, boolean replace);

    /**
     * Whether a refusal means the two stores cannot exchange serialised keys at all.
     *
     * <p>Asked of the engine rather than matched by the caller, because what a store says when it
     * will not read another's dump is the store's own wording. A true answer is not about one key:
     * it says every key in the job will be refused the same way, and that the value-level path is
     * the only one that will work between this pair.
     */
    boolean isIncompatibleFormat(String refusal);
}
