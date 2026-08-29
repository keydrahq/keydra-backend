package io.keydra.keys.dto;

import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Whether a target's changes are being heard, and whether it is saying them.
 *
 * <p>Two facts that look like one and are not. A watch can be open on a server whose notifications
 * are switched off, in which case it is a subscription nothing will ever arrive on — and telling
 * somebody "watching" while that is true would be the page lying quietly, which is the failure this
 * whole phase is about.
 *
 * @param connectionId the target
 * @param database which database is being watched, since a browser is in one of them
 * @param supported whether this store announces its changes at all; false for one that cannot
 * @param announcing whether the server is currently set to send them
 * @param setting what the server's setting says now, for somebody who wants to see it
 * @param wouldBecome what it would be set to if the offer were accepted — a union, never a
 *     replacement
 * @param watching whether Keydra is listening for this caller's sake right now
 * @param watchers how many leases are holding this watch open, this instance's own
 * @param leaseId the caller's lease, to renew or to give back; null when nothing was taken
 * @param leaseExpiresAt when this lease lapses if nobody renews it
 */
@Schema(name = "KeyspaceWatchState", description = "Whether a target's changes are being heard")
public record KeyspaceWatchState(
        Long connectionId,
        int database,
        boolean supported,
        boolean announcing,
        String setting,
        String wouldBecome,
        boolean watching,
        int watchers,
        String leaseId,
        Instant leaseExpiresAt) {}
