package io.keydra.about.dto;

import io.keydra.telemetry.dto.ObservabilityDetails;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Application identity, the build, which instance answered, and what it exports. */
@Schema(name = "AboutResponse", description = "Application identity and build metadata")
public record AboutResponse(
        String name,
        String version,
        BuildDetails build,
        InstanceDetails instance,
        ObservabilityDetails observability) {}
