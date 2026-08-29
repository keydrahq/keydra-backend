package io.keydra.authz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotNull;

/**
 * One edge of the subject graph: what is inside a group.
 *
 * <p>A single table for both kinds of member rather than two, because resolution walks them
 * together: finding everything Alice is in means following her edges and then the edges of whatever
 * they led to, and a walk over two tables is the same walk written twice.
 *
 * <p>Exactly one of {@link #memberUserId} and {@link #memberGroupId} is set. A row with both would
 * be two edges pretending to be one, and a row with neither is an edge to nowhere.
 */
@Entity
@Table(
        name = "group_membership",
        indexes = {
            @Index(name = "idx_group_membership_group", columnList = "group_id"),
            @Index(name = "idx_group_membership_user", columnList = "member_user_id"),
            @Index(name = "idx_group_membership_member_group", columnList = "member_group_id")
        })
public class GroupMembership {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "group_membership_seq")
    public Long id;

    /** The group that contains. */
    @Column(name = "group_id", nullable = false)
    @NotNull
    public Long groupId;

    /** The person it contains, when it contains a person. */
    @Column(name = "member_user_id")
    public Long memberUserId;

    /** The group it contains, when it contains a group. */
    @Column(name = "member_group_id")
    public Long memberGroupId;
}
