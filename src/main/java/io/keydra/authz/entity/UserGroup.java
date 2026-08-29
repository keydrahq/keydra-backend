package io.keydra.authz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;

/**
 * A named set of people, and of other sets.
 *
 * <p>Groups contain groups, which is what lets an organisation state its structure once: granting
 * to "engineers" reaches everybody in "payments-devs" because that group is in it, and neither edge
 * is written twice.
 *
 * <p>The nesting is a graph rather than a tree — a group may be in several — and is kept acyclic on
 * write. A group containing itself would make resolution non-terminating and there is no useful
 * meaning to give it.
 */
@Entity
@Table(
        name = "user_group",
        indexes = {@Index(name = "idx_user_group_name", columnList = "name", unique = true)})
public class UserGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "user_group_seq")
    public Long id;

    @Column(nullable = false, length = 200)
    @NotBlank
    public String name;

    @Column(length = 500)
    public String description;

    /**
     * Set when a provider's claim mapping creates the group rather than a person.
     *
     * <p>Marked because the two are managed differently: a group Keydra maintains from a directory
     * should not be edited here, or the next sign-in will undo the edit.
     */
    @Column(name = "managed_by", length = 64)
    public String managedBy;
}
