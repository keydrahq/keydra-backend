package io.keydra.engine;

/**
 * What became of one key that was written back.
 *
 * <p>Per key rather than per batch, because the three outcomes are genuinely different and a caller
 * that only learns "eleven of two hundred did not land" cannot tell whether the file was from a
 * newer server or the keys were simply already there.
 *
 * @param key the key's name
 * @param written whether the store accepted it
 * @param refusal what the store said when it did not, null when it did
 */
public record RestoreOutcome(String key, boolean written, String refusal) {

    public static RestoreOutcome written(String key) {
        return new RestoreOutcome(key, true, null);
    }

    /** The key was already there and replacing was not asked for. */
    public static RestoreOutcome alreadyThere(String key) {
        return new RestoreOutcome(key, false, null);
    }

    public static RestoreOutcome refused(String key, String reason) {
        return new RestoreOutcome(key, false, reason);
    }

    /** A refusal is a failure; an existing key left alone is not. */
    public boolean isFailure() {
        return refusal != null;
    }
}
