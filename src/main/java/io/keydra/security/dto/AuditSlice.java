package io.keydra.security.dto;

import java.util.List;

/**
 * A page of the audit log as the service answers it, before it becomes a connection.
 *
 * <p>Deliberately not the GraphQL shape: the service does not know whether its caller wants edges
 * and cursors or a plain array, and a type from one transport would make the other translate out of
 * it.
 *
 * @param entries the rows of this page, newest first
 * @param total how many match the filters, across every page
 * @param hasMore whether a row beyond this page exists
 */
public record AuditSlice(List<AuditEntry> entries, long total, boolean hasMore) {}
