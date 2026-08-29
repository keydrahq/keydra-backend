package io.keydra.keys.dto;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Keys to delete.
 *
 * <p>Sent in a body rather than the path because a key may contain any byte, including {@code /}
 * and {@code #}, which no amount of path encoding makes pleasant. The same endpoint therefore
 * serves single and bulk deletion.
 */
@Schema(name = "DeleteKeysRequest", description = "Keys to delete")
public record DeleteKeysRequest(
        @NotEmpty List<String> keys,
        @Schema(
                        description =
                                "The target's own name, required only where the target is guarded."
                                        + " Deleting a selection can empty a keyspace, so a guarded"
                                        + " target asks to be named first.")
                String confirmTarget) {}
