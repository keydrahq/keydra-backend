package io.keydra.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * One setting to change while the server runs.
 *
 * @param name the setting, as the server names it
 * @param value what to set it to; empty is a value for the settings that take one
 */
@Schema(name = "SettingChange", description = "One setting to change while the server runs")
public record SettingChange(@NotBlank String name, @NotNull String value) {}
