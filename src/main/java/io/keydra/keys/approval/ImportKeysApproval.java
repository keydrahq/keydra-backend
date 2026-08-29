package io.keydra.keys.approval;

import io.keydra.approvals.entity.ApprovalKind;
import io.keydra.approvals.entity.ApprovalRequest;
import io.keydra.approvals.service.ApprovalPayloads;
import io.keydra.approvals.service.ApprovalWork;
import io.keydra.keys.approval.KeyApprovalPayloads.ImportKeysPayload;
import io.keydra.keys.dto.ImportKeysRequest;
import io.keydra.keys.service.KeyTransferService;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Writing keys from a dump over what is there, once somebody has agreed to it.
 *
 * <p>The one payload that holds values rather than names, which is why the column it lives in is
 * encrypted: until a colleague answers, somebody's data is sitting in Keydra's database.
 */
@ApplicationScoped
public class ImportKeysApproval implements ApprovalWork {

    /** Enough names to recognise which file this is. The count is already in the sentence. */
    private static final int NAMES_SHOWN = 20;

    private final KeyTransferService transfers;
    private final ApprovalPayloads payloads;

    @Inject
    ImportKeysApproval(KeyTransferService transfers, ApprovalPayloads payloads) {
        this.transfers = transfers;
        this.payloads = payloads;
    }

    @Override
    public ApprovalKind handles() {
        return ApprovalKind.IMPORT_KEYS;
    }

    @Override
    public String describe(ApprovalRequest request) {
        ImportKeysRequest imported = payloads.read(request, ImportKeysPayload.class).request();
        int count = imported.keys().size();
        return "Write "
                + count
                + (count == 1 ? " key" : " keys")
                + (imported.replace() ? ", replacing what is there" : ", keeping what is there");
    }

    @Override
    public List<String> particulars(ApprovalRequest request) {
        List<String> names =
                payloads.read(request, ImportKeysPayload.class).request().keys().stream()
                        .map(one -> one.key())
                        .limit(NAMES_SHOWN)
                        .toList();
        return names;
    }

    @Override
    public Uni<String> run(ApprovalRequest request) {
        ImportKeysRequest imported = payloads.read(request, ImportKeysPayload.class).request();
        return transfers
                .importStream(
                        request.connectionId,
                        Multi.createFrom().iterable(imported.keys()),
                        imported.replace())
                .map(result -> "Wrote " + result.restored() + " keys");
    }
}
