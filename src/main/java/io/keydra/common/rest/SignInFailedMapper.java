package io.keydra.common.rest;

import io.keydra.authz.exception.SignInFailedException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Turns a provider that cannot be reached, or will not answer, into a 409.
 *
 * <p>This only reaches HTTP from the pages where a provider is configured — the sign-in flow itself
 * catches it and sends people back to the login page with something to read. So the caller here is
 * always an administrator saving a provider, and what they need to know is which part of what they
 * typed the other end did not accept.
 *
 * <p>A conflict rather than a bad request: what they sent was well formed, and what it disagrees
 * with is the state of somebody else's server.
 */
public class SignInFailedMapper {

    @ServerExceptionMapper
    public Response failed(SignInFailedException failure) {
        return Response.status(Response.Status.CONFLICT)
                .entity(failure.getMessage())
                .type(MediaType.TEXT_PLAIN)
                .build();
    }
}
