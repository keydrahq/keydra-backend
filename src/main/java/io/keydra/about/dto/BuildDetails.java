package io.keydra.about.dto;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Build-time facts surfaced by {@code /api/v1/about}. */
@Schema(name = "Build", description = "Metadata captured when the server was built")
public record BuildDetails(
        String timestamp, String commit, String javaVersion, String quarkusVersion) {}
