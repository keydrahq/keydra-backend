package io.keydra.authz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * "Whoever the provider calls this belongs in that Keydra group."
 *
 * <p>What makes an existing directory worth having: a deployment states its structure once, in the
 * place that already knows it, and Keydra's grants point at Keydra groups that fill themselves.
 *
 * <p>The mapping is also the boundary of what the provider owns. On every sign-in a person's
 * membership of the groups named here is replaced by what the claim says — so removing somebody
 * from a directory group removes their access — while groups nobody mapped are left alone, because
 * an administrator put them there by hand and a directory has no opinion about them.
 */
@Entity
@Table(
        name = "provider_group_mapping",
        indexes = {
            @Index(name = "idx_provider_group_mapping_provider", columnList = "provider_id")
        })
public class ProviderGroupMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "provider_group_mapping_seq")
    public Long id;

    @Column(name = "provider_id", nullable = false)
    @NotNull
    public Long providerId;

    /** What the provider's claim says — a group name, a role, a directory DN. */
    @Column(name = "claim_value", nullable = false, length = 300)
    @NotBlank
    public String claimValue;

    @Column(name = "group_id", nullable = false)
    @NotNull
    public Long groupId;
}
