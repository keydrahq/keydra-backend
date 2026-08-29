package io.keydra.keys.approval;

import io.keydra.approvals.entity.ApprovalKind;
import io.keydra.approvals.entity.ApprovalRequest;
import io.keydra.approvals.service.ApprovalPayloads;
import io.keydra.approvals.service.ApprovalWork;
import io.keydra.authz.entity.Permission;
import io.keydra.keys.approval.KeyApprovalPayloads.MigrateKeysPayload;
import io.keydra.keys.dto.MigrateKeysRequest;
import io.keydra.keys.service.KeyMigrationService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Moving or copying keys between two targets, once somebody has agreed to it.
 *
 * <p>The only kind with two ends. The request names the source and the destination, and whoever
 * answers it holds {@code migration:run} on both — writing into a target can overwrite it, and
 * moving keys off one empties it.
 */
@ApplicationScoped
public class MigrateKeysApproval implements ApprovalWork {

    private final KeyMigrationService migrations;
    private final ApprovalPayloads payloads;

    @Inject
    MigrateKeysApproval(KeyMigrationService migrations, ApprovalPayloads payloads) {
        this.migrations = migrations;
        this.payloads = payloads;
    }

    @Override
    public ApprovalKind handles() {
        return ApprovalKind.MIGRATE_KEYS;
    }

    @Override
    public String describe(ApprovalRequest request) {
        MigrateKeysRequest migration = payloads.read(request, MigrateKeysPayload.class).request();
        StringBuilder said = new StringBuilder(migration.deleteFromSource() ? "Move " : "Copy ");
        if (migration.keys() != null && !migration.keys().isEmpty()) {
            said.append(migration.keys().size()).append(" named keys");
        } else {
            said.append("every key matching ")
                    .append(migration.match() == null ? "*" : migration.match());
        }
        if (migration.type() != null && !migration.type().isBlank()) {
            said.append(" of type ").append(migration.type());
        }
        said.append(migration.replace() ? ", replacing what is there" : ", keeping what is there");
        return said.toString();
    }

    /**
     * The shaping an approver would not otherwise see.
     *
     * <p>A rename and a script change what arrives on the other side, and a ceiling changes how
     * hard the link is pulled. All three are the difference between agreeing to a copy and agreeing
     * to a different copy, so they are on the page rather than only in the payload.
     */
    @Override
    public List<String> particulars(ApprovalRequest request) {
        MigrateKeysRequest migration = payloads.read(request, MigrateKeysPayload.class).request();
        List<String> said = new ArrayList<>();
        if (migration.rewritesNames()) {
            said.add(
                    "Renames: strips "
                            + orNothing(migration.stripPrefix())
                            + ", adds "
                            + orNothing(migration.addPrefix()));
        }
        if (migration.hasScript()) {
            said.add("Runs a script inside Keydra on every key");
        }
        if (migration.maxKeysPerSecond() != null) {
            said.add("At most " + migration.maxKeysPerSecond() + " keys per second");
        }
        return List.copyOf(said);
    }

    /**
     * A script makes this two things at once, and the second is not about either target.
     *
     * <p>{@code migration:run} is about moving keys. A script runs inside Keydra, on every key, for
     * as long as the walk lasts — that is {@code script:run} on the instance. Asked of the approver
     * as well as of the requester, so a scripted migration is never offered to somebody who would
     * watch it fail after agreeing to it.
     */
    @Override
    public Set<Permission> alsoNeeds(ApprovalRequest request) {
        try {
            return payloads.read(request, MigrateKeysPayload.class).request().hasScript()
                    ? Set.of(Permission.SCRIPT_RUN)
                    : Set.of();
        } catch (IllegalStateException unreadable) {
            // A payload this build cannot read asks for nothing. The run refuses it a moment later
            // with a sentence saying so, which is a better error than one about a permission.
            return Set.of();
        }
    }

    @Override
    public Uni<String> run(ApprovalRequest request) {
        MigrateKeysRequest migration = payloads.read(request, MigrateKeysPayload.class).request();
        return migrations
                .startApproved(request.connectionId, migration, request.requestedBy)
                .map(
                        started ->
                                "Started a migration to connection "
                                        + started.targetConnectionId());
    }

    private static String orNothing(String prefix) {
        return prefix == null || prefix.isEmpty() ? "nothing" : prefix;
    }
}
