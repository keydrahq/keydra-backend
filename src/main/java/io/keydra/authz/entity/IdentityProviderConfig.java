package io.keydra.authz.entity;

import io.keydra.connections.persistence.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * A place people can sign in from.
 *
 * <p>A row rather than an environment variable, which is the whole point of this: adding a way in
 * should not be a redeploy. The consequence is that everything the flow needs has to be here, and
 * that includes the endpoints — discovered once when the provider is saved rather than looked up at
 * every sign-in, so a provider that is momentarily unreachable is a provider whose discovery
 * document is stale, not one nobody can sign in through.
 */
@Entity
@Table(
        name = "identity_provider",
        indexes = {
            @Index(name = "idx_identity_provider_key", columnList = "provider_key", unique = true)
        })
public class IdentityProviderConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "identity_provider_seq")
    public Long id;

    /**
     * What the sign-in URL calls it.
     *
     * <p>Separate from the display name because it appears in a redirect URI, and a redirect URI
     * that changed when somebody corrected a typo in a label would silently stop matching what is
     * registered at the provider.
     */
    @Column(name = "provider_key", nullable = false, length = 64)
    @NotBlank
    public String key;

    @Column(name = "display_name", nullable = false, length = 200)
    @NotBlank
    public String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @NotNull
    public ProviderKind kind = ProviderKind.OIDC;

    @Column(nullable = false)
    public boolean enabled = true;

    /** Where the login page puts it, so the one people use is first. */
    @Column(name = "sort_order", nullable = false)
    public int sortOrder = 0;

    // --- What it was configured with ---------------------------------------

    /** The OIDC issuer. Discovery hangs off it; unused for a plain OAuth 2 provider. */
    @Column(length = 500)
    public String issuer;

    @Column(name = "client_id", nullable = false, length = 300)
    @NotBlank
    public String clientId;

    /**
     * Encrypted at rest, with the same mechanism as a target's password.
     *
     * <p>Never returned by the API — the provider page says whether one is set and nothing more.
     */
    @Column(name = "client_secret", length = 2000)
    @Convert(converter = EncryptedStringConverter.class)
    public String clientSecret;

    /** Space separated, as the protocol carries them. */
    @Column(nullable = false, length = 500)
    public String scopes = "openid profile email";

    // --- What discovery found, or what was typed in for OAuth 2 ------------

    @Column(name = "authorization_endpoint", length = 500)
    public String authorizationEndpoint;

    @Column(name = "token_endpoint", length = 500)
    public String tokenEndpoint;

    /**
     * Where the claims come from.
     *
     * <p>Preferred over the id token even when there is one: it is the only source a plain OAuth 2
     * provider has, and using one source for both kinds means the claim mapping below is one thing
     * to configure rather than two.
     */
    @Column(name = "userinfo_endpoint", length = 500)
    public String userInfoEndpoint;

    // --- How to read the person out of what comes back ---------------------

    /**
     * The claim that names the person for good.
     *
     * <p>Not the username: usernames change, and an account matched by one would become a second
     * account the day somebody married. This is the provider's own identifier — {@code sub} in
     * OIDC, {@code id} at GitHub — and it is what a returning person is recognised by.
     */
    @Column(name = "subject_claim", nullable = false, length = 100)
    public String subjectClaim = "sub";

    @Column(name = "username_claim", nullable = false, length = 100)
    public String usernameClaim = "preferred_username";

    @Column(name = "email_claim", length = 100)
    public String emailClaim = "email";

    @Column(name = "name_claim", length = 100)
    public String nameClaim = "name";

    /** The claim carrying group names, if the provider sends any. */
    @Column(name = "groups_claim", length = 100)
    public String groupsClaim;

    /**
     * Whether somebody arriving for the first time becomes an account.
     *
     * <p>Off means the provider can only sign in people an administrator has already created, which
     * is what a deployment wants when the directory is larger than the set of people who should
     * reach Keydra at all. On either setting they hold nothing until granted: proving who you are
     * is not the same as being allowed in.
     */
    @Column(name = "auto_create_users", nullable = false)
    public boolean autoCreateUsers = true;
}
