package io.keydra.cluster.persistence;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * The targets an instance holds, as one column.
 *
 * <p>A text list rather than rows in a join table. The list changes rarely and is rewritten every
 * beat, so a table would take a delete and an insert per instance per beat to hold what is usually
 * the same answer — and the question it would make cheap, which instances hold this target, is a
 * question about a handful of rows.
 *
 * <p>Ids rather than a foreign key, which means a deleted target can linger here until the next
 * beat. That is correct rather than tolerated: the row reports what this instance held a few
 * seconds ago, and editing it to match a target that has since been deleted would make it a worse
 * report.
 */
@Converter
public class TargetListConverter implements AttributeConverter<List<Long>, String> {

    @Override
    public String convertToDatabaseColumn(List<Long> targets) {
        return join(targets);
    }

    /**
     * The same thing, for the writer that does not go through Hibernate.
     *
     * <p>The announcement is native SQL — {@code last_seen_at} has to be the database's clock — so
     * it cannot lean on the converter and would otherwise grow a second copy of this.
     *
     * <p>Sorted, so that a list which has not changed writes the same string every beat. A column
     * that churned because a set iterated differently would make every diff of this table a lie.
     */
    public static String join(Collection<Long> targets) {
        if (targets == null || targets.isEmpty()) {
            // Empty rather than null: the announcement is native SQL, and a null parameter there is
            // a value PostgreSQL has to infer a type for. Both come back as an empty list.
            return "";
        }
        return targets.stream()
                .filter(Objects::nonNull)
                .sorted()
                .map(String::valueOf)
                .collect(Collectors.joining(","));
    }

    /**
     * Anything unreadable comes back empty rather than throwing.
     *
     * <p>This column is a report, and a roster that failed to load because one instance wrote
     * something odd into it would be the page for diagnosing a fleet taken down by the fleet.
     */
    @Override
    public List<Long> convertToEntityAttribute(String column) {
        if (column == null || column.isBlank()) {
            return List.of();
        }
        List<Long> targets = new ArrayList<>();
        for (String piece : column.split(",")) {
            String trimmed = piece.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                targets.add(Long.valueOf(trimmed));
            } catch (NumberFormatException notAnId) {
                // A report, not a record: skip what cannot be read rather than fail the page.
            }
        }
        return List.copyOf(targets);
    }
}
