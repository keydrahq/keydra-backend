package io.keydra.alerts.rest;

import io.keydra.alerts.exception.AlertRefusedException;
import io.keydra.common.rest.ApiError;
import jakarta.ws.rs.core.Response.Status;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/** Maps a rule or a delivery that cannot work onto an HTTP response. */
public class AlertExceptionMappers {

    /**
     * 409: what was asked for cannot be stored as asked.
     *
     * <p>A rule about a target that is not there, a delivery pointing at nothing, a name already
     * taken, a webhook still in use. Nothing outside was contacted, so nothing outside is being
     * reported — the answer is about the request.
     */
    @ServerExceptionMapper
    public RestResponse<ApiError> refused(AlertRefusedException refused) {
        return RestResponse.status(Status.CONFLICT, new ApiError(refused.getMessage()));
    }
}
