package io.keydra.authz.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * A link, and what became of sending it.
 *
 * <p>Its own file in {@code dto} rather than a record inside the resource that first returned it.
 * Two transports answer with it now, and a resource is one responsibility.
 *
 * @param mailed whether the link was mailed to the account's address
 * @param address the address it went to, when it went anywhere
 * @param link the link itself, so it can be passed on when mail could not send it. Shown once and
 *     never stored in the clear.
 */
@Schema(name = "InvitationIssued", description = "A link, and what became of sending it")
public record InvitationIssued(boolean mailed, String address, String link) {}
