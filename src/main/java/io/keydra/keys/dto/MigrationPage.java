package io.keydra.keys.dto;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One page of migrations, and how many there are.
 *
 * <p>The count is what a pager needs and a list cannot give. Without it a table can offer another
 * page but cannot say whether there is one, which is the difference between paging through a
 * history and guessing at its end.
 *
 * @param jobs the rows of this page, newest first
 * @param total how many the caller can see, across every page
 * @param running how many are moving right now, across every page and whatever the filters say
 */
@Schema(name = "MigrationPage", description = "One page of migrations, with the total that matched")
public record MigrationPage(List<MigrationJob> jobs, long total, long running) {}
