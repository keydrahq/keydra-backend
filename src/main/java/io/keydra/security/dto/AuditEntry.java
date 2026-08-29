package io.keydra.security.dto;

import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One recorded action.
 *
 * @param detail what was acted on — never what it was set to
 */
@Schema(name = "AuditEntry", description = "One recorded action")
public record AuditEntry(
        Long id,
        Instant at,
        String actor,
        String action,
        Long connectionId,
        String detail,
        boolean succeeded) {}
