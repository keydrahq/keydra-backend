package io.keydra.about.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * Which Keydra this is, and whether it is the one doing the work that happens once.
 *
 * <p>Here because "why did my schedule not run" starts with knowing which process was supposed to
 * run it. On a single instance the answer is always the same and costs a line; where there are two,
 * it is the first thing anybody needs.
 *
 * @param id what this instance calls itself
 * @param leader whether this instance currently holds the chores
 * @param chores which instance holds them, which is this one's id when {@code leader}
 */
@Schema(name = "InstanceDetails", description = "This instance and who is doing the shared work")
public record InstanceDetails(String id, boolean leader, String chores) {}
