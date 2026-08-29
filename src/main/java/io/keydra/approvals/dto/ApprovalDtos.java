package io.keydra.approvals.dto;

import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** What crosses the API about operations waiting for a second person. */
public final class ApprovalDtos {

    private ApprovalDtos() {}

    /**
     * The answer an operation gets when it was recorded instead of performed.
     *
     * <p>{@code awaitingApproval} is always true and is there to be read: a caller holding this
     * body has to be able to tell it from the result it asked for without inspecting a status code
     * it may never have looked at, and a field that says so is cheaper to get right than a rule
     * about which fields are absent.
     */
    @Schema(description = "An operation that was recorded and is waiting for somebody to agree")
    public record ApprovalRaised(
            @Schema(description = "The request, which can be watched or withdrawn") Long id,
            @Schema(description = "Always true; this is not the result of the operation")
                    boolean awaitingApproval,
            String kind,
            @Schema(description = "The target the operation is about") String connectionName,
            @Schema(description = "When it stops being answerable") Instant expiresAt,
            @Schema(description = "What to tell the person who asked") String message) {}

    /**
     * One request, as a page shows it.
     *
     * <p>{@code summary} is written from the payload each time this is built rather than stored
     * beside it. A stored sentence is a second description of one thing, and two descriptions can
     * disagree — which for something irreversible is the disagreement that matters.
     */
    @Schema(description = "An operation waiting for, or already given, a second person's answer")
    public record ApprovalSummary(
            Long id,
            String kind,
            String state,
            Long connectionId,
            String connectionName,
            @Schema(description = "The other end of a migration, where there is one")
                    Long secondConnectionId,
            String secondConnectionName,
            @Schema(description = "What the operation would do, in a sentence") String summary,
            @Schema(
                            description =
                                    "Detail an approver needs and a sentence cannot hold — the"
                                        + " first of a long selection of key names, for instance")
                    List<String> particulars,
            String requestedBy,
            Instant requestedAt,
            Instant expiresAt,
            String decidedBy,
            Instant decidedAt,
            @Schema(description = "Why it was declined, or what happened when it ran")
                    String detail,
            @Schema(description = "Whether the caller is the person who asked") boolean mine,
            @Schema(description = "Whether the caller may answer it, which is never their own")
                    boolean canDecide) {}

    /** Saying no, and saying why. */
    public record DeclineRequest(
            @Schema(description = "Why, for the person who asked to read") @Size(max = 500)
                    String reason) {}
}
