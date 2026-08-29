package io.keydra.security.dto;

import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One page of the audit log, and how much of it there is.
 *
 * <p>The count is the reason this exists. A list on its own can fill a table but it cannot fill a
 * pager: "page 3 of ?" is not something to show somebody, and a table that only ever offers "next"
 * cannot say whether the answer they are looking for is two pages away or two hundred. The log is
 * also the one list here that only grows — nothing prunes it — so the ceiling that used to stand in
 * for paging, two hundred rows and no way past them, quietly became a log that could not be read
 * past its most recent afternoon.
 *
 * @param entries the rows of this page, newest first
 * @param total how many rows match the filters, across every page
 */
@Schema(name = "AuditPage", description = "One page of the audit log, with the total that matched")
public record AuditPage(List<AuditEntry> entries, long total) {}
