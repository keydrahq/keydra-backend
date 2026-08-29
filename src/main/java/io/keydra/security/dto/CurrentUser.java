package io.keydra.security.dto;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Who Keydra thinks is asking, and what they may do.
 *
 * <p>The frontend needs this before it draws anything: an action a person cannot take should not be
 * offered, and a login prompt should not appear where there is nothing to log in to.
 *
 * @param name the identity provider's name for them, or "anonymous" when nobody is being identified
 * @param roles what they hold
 * @param securityEnabled false when Keydra is not enforcing anything, which the UI says plainly
 *     rather than presenting an open instance as a secured one
 */
@Schema(name = "CurrentUser", description = "Who is asking and what they may do")
public record CurrentUser(String name, List<String> roles, boolean securityEnabled) {}
