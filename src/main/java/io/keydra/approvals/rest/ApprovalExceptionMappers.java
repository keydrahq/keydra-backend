package io.keydra.approvals.rest;

import io.keydra.approvals.dto.ApprovalDtos.ApprovalRaised;
import io.keydra.approvals.exception.ApprovalRefusedException;
import io.keydra.approvals.exception.ApprovalRequiredException;
import io.keydra.common.rest.ApiError;
import jakarta.ws.rs.core.Response.Status;
import org.jboss.resteasy.reactive.RestResponse;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;

/**
 * Maps the approvals domain's failures onto HTTP responses.
 *
 * <p>Lives with the resource it serves, so the transport depends on the domain and never the other
 * way round — which is what lets an operation in {@code keys} raise one of these without anything
 * shared having to know that approvals exist.
 */
public class ApprovalExceptionMappers {

    /**
     * The operation was recorded rather than performed.
     *
     * <p>202 and not an error, because none of the error codes is true: nothing was refused,
     * nothing was malformed, and the request was accepted. What it must never be is 200 — a script
     * calling {@code /keys/purge} has to be able to tell "recorded" from "done" — so the status
     * says one thing and the body, which names the request, says it again for whoever never looks
     * at a status.
     */
    @ServerExceptionMapper
    public RestResponse<ApprovalRaised> awaiting(ApprovalRequiredException e) {
        return RestResponse.status(Status.ACCEPTED, e.raised());
    }

    /**
     * Something about a request could not be done by this caller.
     *
     * <p>409 rather than 403, and for the reason the guarded target's refusal is one: the caller is
     * allowed to make the request, and what stands in the way is the state of the thing — already
     * answered, not theirs to withdraw, or theirs to have asked for in the first place.
     */
    @ServerExceptionMapper
    public RestResponse<ApiError> refused(ApprovalRefusedException e) {
        return RestResponse.status(Status.CONFLICT, new ApiError(e.getMessage()));
    }
}
