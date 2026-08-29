package io.keydra.tunnels.rest;

import io.keydra.common.rest.ApiError;
import io.keydra.tunnels.exception.TunnelConflictException;
import io.keydra.tunnels.exception.TunnelException;
import jakarta.ws.rs.core.Response.Status;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Maps tunnel failures onto HTTP responses. */
public class TunnelExceptionMappers {

    /**
     * 502: the request was fine and the jump host it named would not have us.
     *
     * <p>Not 500, which would say Keydra broke, and not 400, which would send somebody looking for
     * their own mistake in a request that had none.
     */
    @ServerExceptionMapper
    public RestResponse<ApiError> unreachable(TunnelException failure) {
        return RestResponse.status(Status.BAD_GATEWAY, new ApiError(failure.getMessage()));
    }

    /** 409: what was asked for cannot be stored as asked, and nothing external was reached. */
    @ServerExceptionMapper
    public RestResponse<ApiError> conflict(TunnelConflictException conflict) {
        return RestResponse.status(Status.CONFLICT, new ApiError(conflict.getMessage()));
    }
}
