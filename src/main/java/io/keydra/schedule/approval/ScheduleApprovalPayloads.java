package io.keydra.schedule.approval;

import io.keydra.schedule.dto.ScheduleDtos.ScheduleRequest;

/**
 * What a schedule that needs agreeing to looks like while it is waiting.
 *
 * <p>The request as it was made, and the id of the schedule it would change or nothing where it
 * would make a new one. Nothing else exists in the meantime: a row written now and enabled later
 * would be an arrangement somebody finds and turns on without ever having been asked.
 */
public final class ScheduleApprovalPayloads {

    private ScheduleApprovalPayloads() {}

    public record ScheduleWritePayload(Long scheduleId, ScheduleRequest request) {}
}
