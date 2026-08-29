package io.keydra.engine;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One of a server's settings.
 *
 * <p>RESP has no command that answers "what would this setting have been", so nothing here claims
 * to know a default. What it can say is whether the setting holds anything at all, which is the
 * distinction that matters for the ones where empty means off — requirepass, maxmemory, unixsocket.
 *
 * @param name the setting, as the server names it
 * @param value what it is set to now
 * @param isUnset whether it holds nothing, which for many settings is what "off" looks like
 */
@Schema(name = "ServerSetting", description = "One of a server's settings")
public record ServerSetting(String name, String value, boolean isUnset) {}
