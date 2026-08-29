package io.keydra.security.mapper;

import io.keydra.security.dto.AuditEntry;
import io.keydra.security.entity.AuditEvent;
import java.util.List;
import org.mapstruct.Mapper;

/**
 * Translates an audit row into the shape the API returns.
 *
 * <p>Generated, like every other mapper here, and extracted the moment there were two callers: the
 * REST resource wrote this by hand, and a second hand-written copy in the GraphQL resolver would be
 * two places for one field to be forgotten. With `unmappedTargetPolicy=ERROR`, a column added to
 * the entity and not to the record fails the build instead of arriving as null in one surface and
 * not the other.
 */
@Mapper(componentModel = "jakarta")
public interface AuditMapper {

    AuditEntry toEntry(AuditEvent event);

    List<AuditEntry> toEntries(List<AuditEvent> events);
}
