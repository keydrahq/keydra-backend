package io.keydra.approvals.service;

import io.keydra.approvals.entity.ApprovalKind;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.util.EnumMap;
import java.util.Map;

/**
 * Which piece of code carries out which kind of request.
 *
 * <p>Built from the beans that exist rather than from a list somebody maintains, the same way
 * {@code JobRunner} finds its handlers: a kind with no implementation is a compile-time omission
 * that would otherwise be discovered by an approval that did nothing.
 */
@ApplicationScoped
public class ApprovalWorkshop {

    private final Map<ApprovalKind, ApprovalWork> work = new EnumMap<>(ApprovalKind.class);

    @Inject
    ApprovalWorkshop(Instance<ApprovalWork> discovered) {
        discovered.forEach(one -> work.put(one.handles(), one));
    }

    /** The handler for a kind, or a failure — which is a kind somebody forgot to write. */
    public ApprovalWork workFor(ApprovalKind kind) {
        ApprovalWork found = work.get(kind);
        if (found == null) {
            throw new IllegalStateException("Nothing carries out " + kind);
        }
        return found;
    }
}
