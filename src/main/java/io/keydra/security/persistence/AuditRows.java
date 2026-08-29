package io.keydra.security.persistence;

import io.keydra.security.entity.AuditEvent;
import java.util.List;

/**
 * A page of audit rows as the database gave them up.
 *
 * <p>Events rather than entries, which is why this lives here: it holds entities, and an entity
 * must not travel further than the layer that loaded it.
 *
 * @param rows the entities of this page
 * @param total how many matched the same filters
 * @param hasMore whether a row beyond this page exists — learnt by asking for one extra
 */
public record AuditRows(List<AuditEvent> rows, long total, boolean hasMore) {}
