package io.keydra.connections.rest;

import io.keydra.common.rest.ApiError;
import io.keydra.common.tls.Certificates;
import io.keydra.connections.exception.ConnectionNotFoundException;
import io.keydra.connections.exception.DuplicateConnectionNameException;
import io.keydra.connections.exception.InvalidConnectionException;
import io.keydra.connections.exception.TargetNotNamedException;
import jakarta.ws.rs.core.Response.Status;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Maps connection-domain failures onto HTTP responses.
 *
 * <p>Lives with the resource it serves rather than in a shared module, so the transport depends on
 * the domain and never the other way round.
 */
public class ConnectionExceptionMappers {

    @ServerExceptionMapper
    public RestResponse<ApiError> notFound(ConnectionNotFoundException e) {
        return RestResponse.status(Status.NOT_FOUND, new ApiError(e.getMessage()));
    }

    @ServerExceptionMapper
    public RestResponse<ApiError> duplicateName(DuplicateConnectionNameException e) {
        return RestResponse.status(Status.CONFLICT, new ApiError(e.getMessage()));
    }

    /**
     * A profile refused for what it says, which is a request problem rather than a server one.
     *
     * <p>Every one of these checks exists so that the refusal arrives while somebody is still
     * looking at the form. Until this mapper existed they arrived as 500s with a stack trace, which
     * is the same information a handshake failure gives and in the same unhelpful shape — so the
     * checks were doing their work and the answer was throwing it away.
     */
    @ServerExceptionMapper
    public RestResponse<ApiError> invalid(InvalidConnectionException e) {
        return RestResponse.status(Status.BAD_REQUEST, new ApiError(e.getMessage()));
    }

    /**
     * A guarded target that the caller did not name.
     *
     * <p>409 rather than 400: the request is well formed and the caller is allowed to make it. What
     * is missing is a confirmation, and the state that makes it necessary belongs to the target
     * rather than to the request — which is what a conflict is.
     */
    @ServerExceptionMapper
    public RestResponse<ApiError> notNamed(TargetNotNamedException e) {
        return RestResponse.status(Status.CONFLICT, new ApiError(e.getMessage()));
    }

    /**
     * A certificate or a key that could not be read, said in a sentence a form can show.
     *
     * <p>Beside the one above rather than folded into it: this one is thrown from {@code common},
     * which knows nothing about connections and should go on knowing nothing about them.
     */
    @ServerExceptionMapper
    public RestResponse<ApiError> notUsable(Certificates.NotUsableException e) {
        return RestResponse.status(Status.BAD_REQUEST, new ApiError(e.getMessage()));
    }
}
