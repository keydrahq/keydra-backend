package io.keydra.authz.entity;

/**
 * What a configured identity provider speaks.
 *
 * <p>Two so far, and the difference between them is one endpoint. OIDC providers publish a
 * discovery document and issue an id token; OAuth 2 providers do neither, so their endpoints are
 * typed in and their claims come from whatever their user endpoint returns.
 */
public enum ProviderKind {

    /** Keycloak, Entra, Google, Okta, Auth0 — anything with a discovery document. */
    OIDC,

    /** GitHub, GitLab — an authorization-code flow with no id token and no discovery. */
    OAUTH2
}
