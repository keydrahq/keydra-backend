package io.keydra.security.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A user to create or replace.
 *
 * @param rules the target's own rule syntax, passed through unaltered. Not assembled from
 *     checkboxes: the rule language is the server's, it grows with each version, and a form that
 *     only knows the rules Keydra was written against would prevent anyone from writing the rest.
 */
@Schema(name = "AclUserRequest", description = "A user to create or replace on the target")
public record AclUserRequest(@NotBlank String username, List<String> rules) {

    public List<String> rules() {
        return rules == null ? List.of() : rules;
    }
}
