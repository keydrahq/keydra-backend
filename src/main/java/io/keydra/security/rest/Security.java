package io.keydra.security.rest;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.security.Roles;
import io.keydra.security.dto.AuditEntry;
import io.keydra.security.dto.CurrentUser;
import io.keydra.security.mapper.AuditMapper;
import io.keydra.security.service.AuditService;
import io.keydra.security.service.SecuritySettings;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.PermitAll;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Who is asking, and what everybody has done. */
@Path("/api/v1/security")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Security", description = "Identity, roles and the audit log")
public class Security {

    private final SecurityIdentity identity;
    private final SecuritySettings settings;
    private final AuditService audit;
    private final AuditMapper mapper;

    @Inject
    Security(
            SecurityIdentity identity,
            SecuritySettings settings,
            AuditService audit,
            AuditMapper mapper) {
        this.identity = identity;
        this.settings = settings;
        this.audit = audit;
        this.mapper = mapper;
    }

    @GET
    @Path("/me")
    @PermitAll
    @Operation(
            summary = "Who Keydra thinks is asking, and what they may do",
            description =
                    "Open to anyone, because a client has to be able to ask this before it knows"
                            + " whether it needs to log in.")
    @APIResponse(responseCode = "200", description = "Identity and roles")
    public CurrentUser me() {
        return new CurrentUser(
                identity.isAnonymous() ? audit.actor() : identity.getPrincipal().getName(),
                List.copyOf(identity.getRoles()),
                settings.enabled());
    }

    @GET
    @Path("/audit")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.AUDIT_READ)
    @Operation(
            summary = "What has been done, newest first",
            description =
                    "Records operations that changed something, including ones that failed. Reads"
                            + " are not recorded: a log of every page view buries the entries"
                            + " somebody will actually come looking for.")
    @APIResponse(responseCode = "200", description = "Recorded actions")
    public Uni<List<AuditEntry>> auditLog(
            @QueryParam("actor") String actor,
            @QueryParam("action") String action,
            @QueryParam("connectionId") Long connectionId,
            @QueryParam("since") String since,
            @QueryParam("limit") @DefaultValue("200") int limit) {
        return audit.search(actor, action, connectionId, parseInstant(since), limit)
                .map(mapper::toEntries);
    }

    @GET
    @Path("/audit/actions")
    @RolesAllowed(Roles.ADMIN)
    @RequiresPermission(Permission.AUDIT_READ)
    @Operation(
            summary = "The action names recorded so far",
            description = "So a filter can offer what exists rather than ask for a guess.")
    @APIResponse(responseCode = "200", description = "Recorded action names")
    public Uni<List<String>> auditActions() {
        return audit.actions();
    }

    /** A bad timestamp is treated as no filter rather than as an error. */
    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (java.time.format.DateTimeParseException notATimestamp) {
            return null;
        }
    }
}
