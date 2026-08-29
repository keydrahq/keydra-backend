package io.keydra.common.rest;

import io.keydra.authz.exception.PublicUrlNotConfiguredException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Turns "this instance does not know its own address" into a 500 that says what to set.
 *
 * <p>A 500 rather than a 400, because the caller did nothing wrong: the instance is missing a piece
 * of its own configuration. The message is one of the few allowed through to the caller intact —
 * whoever sees it is an administrator looking at a provider page, and the whole value of the
 * message is the name of the variable to set.
 */
public class PublicUrlNotConfiguredMapper {

    @ServerExceptionMapper
    public Response notConfigured(PublicUrlNotConfiguredException missing) {
        return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                .entity(missing.getMessage())
                .type(MediaType.TEXT_PLAIN)
                .build();
    }
}
