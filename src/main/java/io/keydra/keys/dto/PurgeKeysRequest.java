package io.keydra.keys.dto;

import jakarta.validation.constraints.NotBlank;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Everything a pattern matches, to be deleted.
 *
 * <p>A pattern rather than a list of names, because the case this exists for is "clear this
 * namespace" and "clear everything" — where naming the keys would mean sending thousands of them to
 * describe something the store can walk itself.
 *
 * @param match the glob to delete; required, and deliberately so — an absent pattern that quietly
 *     meant everything would be the most expensive typo in the application
 * @param limit how many keys the walk may take, so a purge cannot run unbounded by accident
 */
@Schema(name = "PurgeKeysRequest", description = "Everything a pattern matches, to be deleted")
public record PurgeKeysRequest(
        @NotBlank String match,
        Integer limit,
        @Schema(description = "The target's own name, required only where the target is guarded")
                String confirmTarget) {

    /** High enough not to surprise anyone clearing a real namespace. */
    public static final int DEFAULT_LIMIT = 10_000_000;

    public int limitOrDefault() {
        return limit == null || limit <= 0 ? DEFAULT_LIMIT : limit;
    }
}
