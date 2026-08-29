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
 * A named set of targets.
 *
 * <p>What a grant is usually about: "the payments team may write the payments servers" is one
 * sentence whether that team runs two servers or twenty, and a group is what keeps it one sentence
 * when the twenty-first arrives.
 *
 * <p>A tree rather than a graph, unlike {@link UserGroup}: a server belongs somewhere, and a group
 * that was in two places at once would make "which environment is this" ambiguous — which is the
 * question these exist to answer.
 */
@Entity
@Table(
        name = "server_group",
        indexes = {@Index(name = "idx_server_group_name", columnList = "name", unique = true)})
public class ServerGroup {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "server_group_seq")
    public Long id;

    @Column(nullable = false, length = 200)
    @NotBlank
    public String name;

    @Column(length = 500)
    public String description;

    /** The group this one is inside, or null for a top-level one. */
    @Column(name = "parent_id")
    public Long parentId;
}
