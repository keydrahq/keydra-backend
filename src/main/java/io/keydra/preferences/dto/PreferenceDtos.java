package io.keydra.preferences.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.Map;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** What crosses the API when somebody's preferences are read or written. */
public final class PreferenceDtos {

    private PreferenceDtos() {}

    /**
     * Everything one person prefers.
     *
     * @param preferences name to value, empty for somebody who has never changed anything — and
     *     also for an instance with enforcement off, where there is no account to keep them with
     * @param stored false when there is nobody to store them for, so the browser knows its own copy
     *     is the only copy rather than assuming the server simply had nothing
     */
    @Schema(name = "Preferences", description = "What one person prefers")
    public record Preferences(Map<String, String> preferences, boolean stored) {}

    /**
     * One preference being set.
     *
     * <p>The value is not validated beyond its length. What a preference means is the interface's
     * business, and a server that knew every switch would be a second place to change whenever the
     * interface grew one.
     */
    @Schema(name = "PreferenceRequest", description = "One preference to set")
    public record PreferenceRequest(
            @NotBlank @Size(max = 64) String name, @NotBlank @Size(max = 4096) String value) {}
}
