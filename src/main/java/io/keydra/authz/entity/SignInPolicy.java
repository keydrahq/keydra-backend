package io.keydra.authz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/**
 * What this installation asks of everybody who signs in.
 *
 * <p>One row, and the key is a constant so there cannot be a second. An installation-wide decision
 * is not a thing that can be true twice, and a table that allowed two would eventually hold two
 * with nothing to say which one applied.
 *
 * <p>No row at all is a valid state and means nothing is required. That is deliberate rather than
 * an oversight to tidy up later: development builds the schema from these classes rather than from
 * the migrations, so a policy that only existed once a migration had seeded it would be a policy
 * that is absent in development and present everywhere else.
 */
@Entity
@Table(name = "sign_in_policy")
public class SignInPolicy {

    /** The only id there is. */
    public static final short ONLY = 1;

    @Id public Short id = ONLY;

    /**
     * Whether a local account has to have a confirmed authenticator to do anything.
     *
     * <p>Local only, and the reasoning is in {@code SignInPolicies}: an account that signs in
     * through an identity provider proved who it was somewhere else, and Keydra is not the
     * authority on how that was done.
     */
    @Column(name = "second_factor_required", nullable = false)
    public boolean secondFactorRequired;

    @Column(name = "changed_at")
    public Instant changedAt;

    /**
     * Who last changed it.
     *
     * <p>A name rather than an account id. The audit log holds the event with everything else about
     * it; this is the line shown beside the switch, and it has to still read sensibly after the
     * account that flipped it has been deleted.
     */
    @Column(name = "changed_by", length = 200)
    public String changedBy;
}
