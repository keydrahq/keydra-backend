package io.keydra.authz.dto;

import io.keydra.authz.entity.ProviderKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * The wire shapes for places people can sign in from.
 *
 * <p>Two audiences, and the difference between them is the whole of why a client secret is safe
 * here. {@link SignInOption} is what anybody at the login page may see: a name and a link.
 * Everything else requires the permission to manage providers, and even then the secret goes one
 * way.
 */
public final class ProviderDtos {

    private ProviderDtos() {}

    /**
     * A button on the login page.
     *
     * <p>Open to anybody, necessarily — somebody has to be able to see how to sign in before they
     * have. It carries nothing but a name and the key that starts the flow.
     */
    @Schema(name = "SignInOption", description = "A way in, as the login page sees it")
    public record SignInOption(String key, String displayName, ProviderKind kind) {}

    /**
     * A provider as an administrator sees it.
     *
     * @param hasClientSecret whether one is stored; the secret itself is never returned
     * @param endpointsDiscovered whether Keydra knows where to send people, which is the first
     *     question when a sign-in does not work
     */
    @Schema(name = "ProviderSummary", description = "A configured identity provider")
    public record ProviderSummary(
            Long id,
            String key,
            String displayName,
            ProviderKind kind,
            boolean enabled,
            int sortOrder,
            String issuer,
            String clientId,
            boolean hasClientSecret,
            String scopes,
            String authorizationEndpoint,
            String tokenEndpoint,
            String userInfoEndpoint,
            boolean endpointsDiscovered,
            String subjectClaim,
            String usernameClaim,
            String emailClaim,
            String nameClaim,
            String groupsClaim,
            boolean autoCreateUsers,
            String redirectUri,
            List<GroupMappingSummary> groupMappings) {}

    /**
     * A provider to create or change.
     *
     * <p>An absent client secret keeps the stored one, for the same reason an absent password does:
     * the API never returns one, so an edit form arrives with the field empty.
     */
    @Schema(name = "ProviderRequest", description = "An identity provider to create or change")
    public record ProviderRequest(
            @NotBlank
                    @Pattern(
                            regexp = "[a-z0-9][a-z0-9-]{0,62}",
                            message =
                                    "A key is lowercase letters, digits and hyphens — it appears in"
                                            + " a redirect URI")
                    String key,
            @NotBlank String displayName,
            @NotNull ProviderKind kind,
            Boolean enabled,
            Integer sortOrder,
            String issuer,
            @NotBlank String clientId,
            String clientSecret,
            String scopes,
            String authorizationEndpoint,
            String tokenEndpoint,
            String userInfoEndpoint,
            String subjectClaim,
            String usernameClaim,
            String emailClaim,
            String nameClaim,
            String groupsClaim,
            Boolean autoCreateUsers) {}

    @Schema(name = "GroupMappingSummary", description = "A claim value that puts people in a group")
    public record GroupMappingSummary(Long id, String claimValue, Long groupId, String groupName) {}

    @Schema(name = "GroupMappingRequest", description = "A claim value to map to a group")
    public record GroupMappingRequest(@NotBlank String claimValue, @NotNull Long groupId) {}
}
