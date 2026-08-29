package io.keydra.common.rest;

import io.keydra.authz.exception.AuthzConflictException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Turns an edit the permission model cannot accept into the 409 it is.
 *
 * <p>Not a 400: the request was well formed and the caller was allowed to make it. What it
 * conflicts with is the state of the model — a name already taken, a group that would end up inside
 * itself — which is a thing that can change rather than a thing the caller got wrong.
 */
public class AuthzConflictMapper {

    @ServerExceptionMapper
    public Response conflict(AuthzConflictException conflict) {
        return Response.status(Response.Status.CONFLICT)
                .entity(conflict.getMessage())
                .type(MediaType.TEXT_PLAIN)
                .build();
    }
}
