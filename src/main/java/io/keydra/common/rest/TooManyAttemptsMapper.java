package io.keydra.common.rest;

import io.keydra.authz.exception.TooManyAttemptsException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Turns a refused-without-checking into the 429 it is.
 *
 * <p>A 429 rather than the 401 a wrong password gets, and that is a deliberate difference: this
 * request was not answered, and telling the client to wait is the only thing that makes it stop
 * rather than retry immediately. The {@code Retry-After} header is the machine-readable half of the
 * same sentence.
 *
 * <p>It says nothing about which limit was reached or whether the account exists. Either would
 * answer for free the question the guessing is being done to answer.
 */
public class TooManyAttemptsMapper {

    @ServerExceptionMapper
    public Response tooMany(TooManyAttemptsException refused) {
        return Response.status(429)
                .header("Retry-After", Math.max(1, refused.retryAfter().toSeconds()))
                .entity(refused.getMessage())
                .type(MediaType.TEXT_PLAIN)
                .build();
    }
}
