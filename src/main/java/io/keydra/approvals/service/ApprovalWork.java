package io.keydra.approvals.service;

import io.keydra.approvals.entity.ApprovalKind;
import io.keydra.approvals.entity.ApprovalRequest;
import io.keydra.authz.entity.Permission;
import io.smallrye.mutiny.Uni;
import java.util.List;
import java.util.Set;

/**
 * What one kind of approved operation actually does.
 *
 * <p>Implemented in the domain that owns the work rather than here, and discovered as a bean, so
 * this package never learns what a key or a schedule is. The dependency runs one way: {@code keys}
 * knows it may have to ask, and the asking never has to know what it is asking about.
 *
 * <p>The same shape as {@code schedule.job.JobHandler}, which is the other place in Keydra where
 * work is described now and carried out later, and for the same reasons.
 */
public interface ApprovalWork {

    /** The kind this carries out. One implementation each. */
    ApprovalKind handles();

    /**
     * What the operation would do, in a sentence, written from the payload every time it is read.
     *
     * <p>Without target names: the page has both ends as fields and draws them itself, so a
     * sentence that repeated them would be a second place they could be wrong.
     */
    String describe(ApprovalRequest request);

    /**
     * What a sentence cannot hold and an approver still needs — the first of a long selection of
     * key names, the cadence a schedule would run on.
     *
     * <p>Bounded by the implementation. Somebody agreeing to a deletion of five thousand keys is
     * not helped by five thousand lines, and the count is already in the sentence.
     */
    default List<String> particulars(ApprovalRequest request) {
        return List.of();
    }

    /**
     * What this needs on top of {@link ApprovalKind#required()}.
     *
     * <p>Asked twice, of two people, and that is the point of it being here rather than inline
     * anywhere: the approver has to hold it, so a request they cannot see through to the end is
     * never one they are offered; and the requester has to still hold it when the work runs, so an
     * approval granted after their access was taken away does nothing.
     */
    default Set<Permission> alsoNeeds(ApprovalRequest request) {
        return Set.of();
    }

    /**
     * Carries it out, and answers with what happened in a sentence.
     *
     * <p>Called once, on a request the runner has already moved out of {@code PENDING}, so a
     * handler never has to wonder whether two people pressed at the same moment.
     */
    Uni<String> run(ApprovalRequest request);
}
