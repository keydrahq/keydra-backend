package io.keydra.common.rest;

import io.keydra.engine.OperationUnsupportedException;
import jakarta.ws.rs.core.Response.Status;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * A store with no such operation, answered as 501 rather than 500.
 *
 * <p>Here rather than in one domain's own {@code rest} package because the refusal is not one
 * domain's: the same exception comes back from a key being renamed, a value being changed and a
 * time to live being set, and it says the same thing in all three. That is the shape the mappers
 * already in this package have.
 */
public class OperationUnsupportedMapper {

    @ServerExceptionMapper
    public RestResponse<ApiError> unsupported(OperationUnsupportedException e) {
        return RestResponse.status(Status.NOT_IMPLEMENTED, new ApiError(e.getMessage()));
    }
}
