package io.keydra.keys.dto;

import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What to move, where to, and what to do to it on the way.
 *
 * @param targetConnectionId the target to write to
 * @param keys an explicit list of names, when the caller already knows them
 * @param match a glob the walk selects on, when it does not
 * @param type only keys of this type, spelled as the store spells it — {@code hash}, {@code
 *     stream}. Narrowed at the walk rather than after it, so keys of other types are never read.
 *     The reason it earns its place is that a target need not support every type the source has:
 *     Garnet has no streams at all, so "everything except the streams" is the difference between a
 *     migration that works and one that reports failures nobody can do anything about.
 * @param stripPrefix a prefix removed from each name before it is written, or null
 * @param addPrefix a prefix put on each name before it is written, or null. With {@code
 *     stripPrefix} this is a rename: {@code staging:} off and {@code prod:} on. Both are applied to
 *     the destination only — the source is read, and deleted if asked, under the name it has.
 * @param script a Lua script deciding what happens to each key, or null. It sees a key's name and
 *     what is left of its life, and answers with a name, an expiry, or a refusal to move it at all
 *     — see {@code io.keydra.keys.script.KeyScript} for the contract and {@code
 *     io.keydra.keys.script.SafeLua} for what the interpreter is not allowed to do. Needs {@code
 *     script:run} on the instance as well as the permission to run the migration, and is refused
 *     outright where {@code keydra.keys.scripting.enabled} is false.
 * @param maxKeysPerSecond a ceiling on how fast to go, or null for as fast as the link allows. A
 *     migration is usually run against a server somebody else is using, and the tool that empties
 *     the link is also the tool that makes the application on the other end slow.
 * @param replace whether to overwrite a key the target already has
 * @param deleteFromSource whether to remove each key from the source once the target has it
 * @param limit a ceiling on how many keys to move
 */
public record MigrateKeysRequest(
        @NotNull Long targetConnectionId,
        List<String> keys,
        String match,
        String type,
        String stripPrefix,
        String addPrefix,
        String script,
        Integer maxKeysPerSecond,
        boolean replace,
        boolean deleteFromSource,
        Integer limit,
        @Schema(
                        description =
                                "The destination's own name, required only where the destination is"
                                    + " guarded. The guard is on what a migration writes into, not"
                                    + " on what it reads from.")
                String confirmTarget,
        @Schema(
                        description =
                                "The source's own name, required only when deleteFromSource is set"
                                    + " and the source is guarded. Moving keys off a target empties"
                                    + " it as surely as deleting them does.")
                String confirmSource) {

    /** Migrations are expected to be large, so the ceiling is high enough not to be a surprise. */
    public static final int DEFAULT_LIMIT = 10_000_000;

    public int limitOrDefault() {
        return limit == null || limit <= 0 ? DEFAULT_LIMIT : limit;
    }

    /** Whether a script was given at all, which most migrations do not give. */
    public boolean hasScript() {
        return script != null && !script.isBlank();
    }

    /** Whether any renaming is asked for at all, which most migrations do not ask for. */
    public boolean rewritesNames() {
        return notBlank(stripPrefix) || notBlank(addPrefix);
    }

    /**
     * The name a key is written under.
     *
     * <p>A key whose name does not start with {@code stripPrefix} is written unchanged rather than
     * skipped or mangled: the caller asked for a prefix to be removed where it is there, and a walk
     * selected by one glob can still turn up names that do not share a prefix.
     */
    public String destinationName(String source) {
        String stripped =
                notBlank(stripPrefix) && source.startsWith(stripPrefix)
                        ? source.substring(stripPrefix.length())
                        : source;
        return notBlank(addPrefix) ? addPrefix + stripped : stripped;
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isEmpty();
    }
}
