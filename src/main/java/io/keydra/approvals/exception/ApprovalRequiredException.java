package io.keydra.approvals.exception;

import io.keydra.approvals.dto.ApprovalDtos.ApprovalRaised;
import io.smallrye.graphql.api.ErrorCode;

/**
 * The operation was recorded rather than performed, because this target asks for two people.
 *
 * <p>Not a failure, which is why the answer it becomes is 202 rather than a 4xx: nothing was
 * refused, nothing was malformed, and the request was accepted. What it must not become is 200 —
 * something calling {@code /keys/purge} from a script has to be able to tell "recorded" from
 * "done", and only a different status says so without the caller having to read a body it did not
 * expect.
 *
 * <p>An exception rather than a return value for the reason phase 59's refusal is one: four
 * services answer with four different types, and expressing one branch in all of them would mean a
 * wrapper on each.
 *
 * <p>The GraphQL surface has no status code to say this with, so it says it in the error's code
 * extension instead. That annotation sits on a domain type rather than in {@code graphql} because
 * it is the only place the framework reads one — and what it names is the same fact the 202 names,
 * said to the surface that cannot use a status.
 */
@ErrorCode(ApprovalRequiredException.CODE)
public class ApprovalRequiredException extends RuntimeException {

    /**
     * What both surfaces call this.
     *
     * <p>Matched by the browser to tell "recorded" from "refused", which look identical on a
     * surface where every answer is 200.
     */
    public static final String CODE = "approval-required";

    private final transient ApprovalRaised raised;

    public ApprovalRequiredException(ApprovalRaised raised) {
        super(raised.message());
        this.raised = raised;
    }

    public ApprovalRaised raised() {
        return raised;
    }
}
