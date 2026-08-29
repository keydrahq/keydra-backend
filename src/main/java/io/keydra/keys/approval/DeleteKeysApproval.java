package io.keydra.keys.approval;

import io.keydra.approvals.entity.ApprovalKind;
import io.keydra.approvals.entity.ApprovalRequest;
import io.keydra.approvals.service.ApprovalPayloads;
import io.keydra.approvals.service.ApprovalWork;
import io.keydra.keys.approval.KeyApprovalPayloads.DeleteKeysPayload;
import io.keydra.keys.service.KeyService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/** Deleting a named selection, once somebody has agreed to it. */
@ApplicationScoped
public class DeleteKeysApproval implements ApprovalWork {

    /**
     * How many names an approver is shown.
     *
     * <p>Somebody agreeing to a deletion of five thousand keys is not helped by five thousand
     * lines, and the count is already in the sentence. Enough to recognise which selection this is.
     */
    private static final int NAMES_SHOWN = 20;

    private final KeyService keys;
    private final ApprovalPayloads payloads;

    @Inject
    DeleteKeysApproval(KeyService keys, ApprovalPayloads payloads) {
        this.keys = keys;
        this.payloads = payloads;
    }

    @Override
    public ApprovalKind handles() {
        return ApprovalKind.DELETE_KEYS;
    }

    @Override
    public String describe(ApprovalRequest request) {
        DeleteKeysPayload payload = payloads.read(request, DeleteKeysPayload.class);
        int count = payload.keys().size();
        return "Delete "
                + count
                + (count == 1 ? " key" : " keys")
                + (payload.database() == null ? "" : " on database " + payload.database());
    }

    @Override
    public List<String> particulars(ApprovalRequest request) {
        List<String> names = payloads.read(request, DeleteKeysPayload.class).keys();
        return names.size() <= NAMES_SHOWN
                ? List.copyOf(names)
                : List.copyOf(names.subList(0, NAMES_SHOWN));
    }

    @Override
    public Uni<String> run(ApprovalRequest request) {
        DeleteKeysPayload payload = payloads.read(request, DeleteKeysPayload.class);
        return keys.deleteApproved(request.connectionId, payload.database(), payload.keys())
                .map(result -> "Deleted " + result.affected() + " keys");
    }
}
