package io.keydra.common.rest;

import io.keydra.schedule.exception.ScheduleRefusedException;
import jakarta.ws.rs.core.Response.Status;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Turns a schedule that cannot work into the 409 it is.
 *
 * <p>A conflict rather than a bad request: what was sent is well formed, and what it disagrees with
 * is what the job would need to do — a cron nobody can parse, a second target that is not there.
 *
 * <p>Answered as an {@link ApiError} like every other refusal in the API, so one client-side reader
 * handles all of them; a plain-text body here would be the single case that needs its own.
 */
public class ScheduleRefusedMapper {

    @ServerExceptionMapper
    public RestResponse<ApiError> refused(ScheduleRefusedException refusal) {
        return RestResponse.status(Status.CONFLICT, new ApiError(refusal.getMessage()));
    }
}
