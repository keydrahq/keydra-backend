package io.keydra.keys.rest;

import io.keydra.common.rest.ApiError;
import io.keydra.keys.exception.KeyNotFoundException;
import io.keydra.keys.exception.MigrationRefusedException;
import io.keydra.keys.exception.TransferUnsupportedException;
import jakarta.ws.rs.core.Response.Status;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Maps key-domain failures onto HTTP responses. */
public class KeyExceptionMappers {

    @ServerExceptionMapper
    public RestResponse<ApiError> notFound(KeyNotFoundException e) {
        return RestResponse.status(Status.NOT_FOUND, new ApiError(e.getMessage()));
    }

    /** 409: nothing about the request is malformed, the two ends are simply the same target. */
    @ServerExceptionMapper
    public RestResponse<ApiError> refused(MigrationRefusedException e) {
        return RestResponse.status(Status.CONFLICT, new ApiError(e.getMessage()));
    }

    /**
     * 501, not 400: the request was well formed and the store simply cannot do this. A client that
     * gets 400 looks for its own mistake.
     */
    @ServerExceptionMapper
    public RestResponse<ApiError> unsupported(TransferUnsupportedException e) {
        return RestResponse.status(Status.NOT_IMPLEMENTED, new ApiError(e.getMessage()));
    }
}
