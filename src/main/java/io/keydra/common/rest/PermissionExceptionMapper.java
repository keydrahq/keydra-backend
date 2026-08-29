package io.keydra.common.rest;

import io.keydra.authz.exception.PermissionDeniedException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Turns a refusal into the 403 it is.
 *
 * <p>Cross-cutting rather than per-domain, unlike the other mappers here: a permission is required
 * by endpoints in every domain, and a mapper per domain would be the same eight lines repeated
 * until one of them drifted.
 *
 * <p>The message names the permission that was missing. Somebody who has been refused needs to be
 * able to ask for the right thing, and an administrator reading the audit log needs to know what to
 * grant — neither is served by a bare "forbidden".
 */
public class PermissionExceptionMapper {

    @ServerExceptionMapper
    public Response denied(PermissionDeniedException denied) {
        return Response.status(Response.Status.FORBIDDEN)
                .entity(denied.getMessage())
                .type(MediaType.TEXT_PLAIN)
                .build();
    }
}
