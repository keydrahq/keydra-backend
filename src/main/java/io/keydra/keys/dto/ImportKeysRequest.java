package io.keydra.keys.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * An import: the keys to write, and whether they may overwrite what is already there.
 *
 * <p>Replacing is off unless asked for. An import is usually a restore into a store that has moved
 * on since the export was taken, and quietly overwriting the newer data is the one outcome nobody
 * wants by default.
 */
public record ImportKeysRequest(
        @NotNull List<@Valid ExportedKey> keys,
        boolean replace,
        @Schema(
                        description =
                                "The target's own name, required only where the target is guarded."
                                        + " Importing writes over what is there, which is why it is"
                                        + " asked for here and not on a single write.")
                String confirmTarget) {}
