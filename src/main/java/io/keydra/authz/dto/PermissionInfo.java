package io.keydra.authz.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One permission, described.
 *
 * <p>Its own file in {@code dto} rather than a record inside the resource that happened to return
 * it first. It crosses the API, two transports return it, and a resource is one responsibility.
 *
 * @param name what the API calls it, which is what a role request carries
 * @param id the {@code domain:verb} form a person reads
 * @param level whether it is about a target or about Keydra itself
 */
@Schema(name = "PermissionInfo", description = "One permission a role can carry")
public record PermissionInfo(String name, String id, String level) {}
