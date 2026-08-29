package io.keydra.keys.persistence;

import io.keydra.keys.entity.MigrationRun;
import java.util.List;

/**
 * A page of rows as the database gave them up.
 *
 * <p>Rows rather than jobs, which is why this lives here and not in {@code dto}: it holds entities,
 * and an entity must not travel further than the layer that loaded it. The service turns these into
 * {@link io.keydra.keys.dto.MigrationSlice}, and that is the only thing anything above sees.
 *
 * @param rows the entities of this page
 * @param total how many matched the same filters
 * @param running how many are in the running state, whatever the filters said
 * @param hasMore whether a row beyond this page exists — learnt by asking for one extra
 */
public record MigrationRows(List<MigrationRun> rows, long total, long running, boolean hasMore) {}
