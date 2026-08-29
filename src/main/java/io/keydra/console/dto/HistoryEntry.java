package io.keydra.console.dto;

import java.time.Instant;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** A previously executed command line. */
@Schema(name = "HistoryEntry", description = "A previously executed command line")
public record HistoryEntry(Long id, String line, Instant executedAt) {}
