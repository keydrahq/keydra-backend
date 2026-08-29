package io.keydra.backup.rest;

import io.keydra.backup.exception.BackupFailedException;
import io.keydra.backup.exception.DestinationConflictException;
import io.keydra.common.rest.ApiError;
import jakarta.ws.rs.core.Response.Status;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Maps backup failures onto HTTP responses. */
public class BackupExceptionMappers {

    /**
     * 502: the request was fine and the place it named would not do it.
     *
     * <p>Not 500, which would say Keydra broke, and not 400, which would send somebody looking for
     * their own mistake in a request that had none. A bucket that refuses the credentials, an SFTP
     * host that will not answer and a directory that is not writable are all the same thing from
     * here — something upstream failed, and the message says which.
     */
    @ServerExceptionMapper
    public RestResponse<ApiError> failed(BackupFailedException failure) {
        return RestResponse.status(Status.BAD_GATEWAY, new ApiError(failure.getMessage()));
    }

    /**
     * 409: what was asked for cannot be stored as asked.
     *
     * <p>A name already taken, a destination that is not there, a kind missing the one field it
     * cannot work without. Nothing external was reached, so nothing external is being reported.
     */
    @ServerExceptionMapper
    public RestResponse<ApiError> conflict(DestinationConflictException conflict) {
        return RestResponse.status(Status.CONFLICT, new ApiError(conflict.getMessage()));
    }
}
