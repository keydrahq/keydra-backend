package io.keydra.analysis.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One value shape's share of the memory.
 *
 * <p>Worth separating from the namespaces because the answer is different in kind: a namespace says
 * which feature is expensive, and a type says whether the expense is a design choice — ten thousand
 * hashes and ten thousand strings holding the same data do not cost the same.
 */
@Schema(name = "TypeUsage", description = "One value shape's share of the memory")
public record TypeUsage(String type, long keys, long bytes) {}
