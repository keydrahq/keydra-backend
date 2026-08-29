package io.keydra.keys.approval;

import io.keydra.approvals.entity.ApprovalKind;
import io.keydra.approvals.entity.ApprovalRequest;
import io.keydra.approvals.service.ApprovalPayloads;
import io.keydra.approvals.service.ApprovalWork;
import io.keydra.keys.approval.KeyApprovalPayloads.PurgeKeysPayload;
import io.keydra.keys.service.KeyService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/** Deleting everything a glob matches, once somebody has agreed to it. */
@ApplicationScoped
public class PurgeKeysApproval implements ApprovalWork {

    private final KeyService keys;
    private final ApprovalPayloads payloads;

    @Inject
    PurgeKeysApproval(KeyService keys, ApprovalPayloads payloads) {
        this.keys = keys;
        this.payloads = payloads;
    }

    @Override
    public ApprovalKind handles() {
        return ApprovalKind.PURGE_KEYS;
    }

    @Override
    public String describe(ApprovalRequest request) {
        PurgeKeysPayload payload = payloads.read(request, PurgeKeysPayload.class);
        return "Delete every key matching " + payload.request().match() + onDatabase(payload);
    }

    @Override
    public Uni<String> run(ApprovalRequest request) {
        PurgeKeysPayload payload = payloads.read(request, PurgeKeysPayload.class);
        return keys.purgeApproved(request.connectionId, payload.database(), payload.request())
                .map(result -> "Deleted " + result.affected() + " keys");
    }

    /**
     * Named only where one was chosen.
     *
     * <p>Absent is not zero: zero is a database, and a request that read "on database 0" when the
     * profile opens somewhere else would be describing an operation that is not the one stored.
     */
    private static String onDatabase(PurgeKeysPayload payload) {
        return payload.database() == null ? "" : " on database " + payload.database();
    }
}
