package io.keydra.schedule.approval;

import io.keydra.approvals.entity.ApprovalKind;
import io.keydra.approvals.entity.ApprovalRequest;
import io.keydra.approvals.service.ApprovalPayloads;
import io.keydra.approvals.service.ApprovalWork;
import io.keydra.authz.entity.Permission;
import io.keydra.schedule.approval.ScheduleApprovalPayloads.ScheduleWritePayload;
import io.keydra.schedule.dto.ScheduleDtos.ScheduleRequest;
import io.keydra.schedule.service.ScheduleService;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Arranging work that would empty a target, once somebody has agreed to it.
 *
 * <p>Approving this creates the schedule; declining it leaves nothing behind. What the schedule
 * then does at three in the morning is not asked again, which is the same decision phase 59 made
 * about naming a target: a confirmation is about somebody's intent at the moment of asking, and a
 * schedule is asked for once.
 */
@ApplicationScoped
public class ScheduleWriteApproval implements ApprovalWork {

    private final ScheduleService schedules;
    private final ApprovalPayloads payloads;

    @Inject
    ScheduleWriteApproval(ScheduleService schedules, ApprovalPayloads payloads) {
        this.schedules = schedules;
        this.payloads = payloads;
    }

    @Override
    public ApprovalKind handles() {
        return ApprovalKind.SCHEDULE_WRITE;
    }

    @Override
    public String describe(ApprovalRequest request) {
        ScheduleWritePayload payload = payloads.read(request, ScheduleWritePayload.class);
        ScheduleRequest asked = payload.request();
        return (payload.scheduleId() == null ? "Arrange " : "Change ")
                + asked.jobType().name()
                + " to run unattended, as \""
                + asked.name()
                + "\"";
    }

    @Override
    public List<String> particulars(ApprovalRequest request) {
        ScheduleRequest asked = payloads.read(request, ScheduleWritePayload.class).request();
        List<String> said = new ArrayList<>();
        said.add("Cron: " + asked.cron());
        if (asked.enabled() != null && !asked.enabled()) {
            said.add("Written switched off");
        }
        return List.copyOf(said);
    }

    /**
     * What the work itself needs, on top of arranging it.
     *
     * <p>{@code schedule:manage} is a way of doing something later, not a way of doing something
     * you may not do — so whoever agrees to a nightly flush holds what a flush needs, and the
     * request is never offered to somebody who could not have run it by hand.
     */
    @Override
    public Set<Permission> alsoNeeds(ApprovalRequest request) {
        try {
            return Set.of(
                    payloads.read(request, ScheduleWritePayload.class)
                            .request()
                            .jobType()
                            .required());
        } catch (RuntimeException unreadable) {
            return Set.of();
        }
    }

    @Override
    public Uni<String> run(ApprovalRequest request) {
        ScheduleWritePayload payload = payloads.read(request, ScheduleWritePayload.class);
        return (payload.scheduleId() == null
                        ? schedules.createApproved(payload.request(), request.requestedBy)
                        : schedules.updateApproved(payload.scheduleId(), payload.request()))
                .map(saved -> "Schedule \"" + saved.name() + "\" is arranged");
    }
}
