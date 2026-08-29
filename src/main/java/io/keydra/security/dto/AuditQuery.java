package io.keydra.security.dto;

import java.time.Instant;

/**
 * What narrows a page of the audit log.
 *
 * <p>A value object rather than five parameters threaded through three layers. The filters are
 * independent and a caller may use any of them, so a method per combination is a method per
 * combination; adding one means adding a field here.
 *
 * @param actor only this account's actions, or null for everyone's
 * @param action only this kind of action, or null for all kinds
 * @param connectionId only actions against this target, or null for all of them
 * @param since only what happened after this moment, or null for the whole log
 */
public record AuditQuery(String actor, String action, Long connectionId, Instant since) {}
