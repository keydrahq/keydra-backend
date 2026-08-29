package io.keydra.authz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * The sentence the whole model is built on: this subject holds this role on this scope.
 *
 * <p>Everything else is derived. What somebody may do to a target is the union of the permissions
 * of every role granted to them, or to any group containing them, on that target or on anything
 * containing it.
 *
 * <p>There is no negative form. A system with both grants and denials is one where "why can Alice
 * not see this" is a search rather than a lookup — so absence is the denial, and absence is visible
 * on the page that lists what is there.
 */
@Entity
@Table(
        name = "authz_grant",
        indexes = {
            @Index(name = "idx_grant_subject", columnList = "subject_type,subject_id"),
            @Index(name = "idx_grant_scope", columnList = "scope_type,scope_id")
        })
public class Grant {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "authz_grant_seq")
    public Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type", nullable = false, length = 16)
    @NotNull
    public SubjectType subjectType;

    @Column(name = "subject_id", nullable = false)
    @NotNull
    public Long subjectId;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 16)
    @NotNull
    public ScopeType scopeType;

    /** Null for the instance scope, which is one thing and needs no identifier. */
    @Column(name = "scope_id")
    public Long scopeId;

    @Column(name = "role_id", nullable = false)
    @NotNull
    public Long roleId;

    @Column(name = "granted_at", nullable = false)
    public Instant grantedAt = Instant.now();

    /** Who made the grant, for the audit trail that a permission change deserves. */
    @Column(name = "granted_by", length = 200)
    public String grantedBy;
}
