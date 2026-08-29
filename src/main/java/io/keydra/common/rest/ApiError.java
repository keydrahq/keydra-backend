package io.keydra.common.rest;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** Uniform error body for failed API calls. */
@Schema(name = "ApiError", description = "Error response")
public record ApiError(String message) {}
