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
 * Which group a target is in.
 *
 * <p>A table rather than a column on the profile, because a target can be in more than one: a
 * server is both "production" and "payments", and a model that made somebody choose would have them
 * granting twice.
 */
@Entity
@Table(
        name = "server_group_member",
        indexes = {
            @Index(name = "idx_server_group_member_group", columnList = "group_id"),
            @Index(name = "idx_server_group_member_connection", columnList = "connection_id")
        })
public class ServerGroupMember {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "server_group_member_seq")
    public Long id;

    @Column(name = "group_id", nullable = false)
    @NotNull
    public Long groupId;

    @Column(name = "connection_id", nullable = false)
    @NotNull
    public Long connectionId;
}
