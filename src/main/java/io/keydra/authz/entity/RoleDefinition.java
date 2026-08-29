package io.keydra.authz.entity;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;
import jakarta.validation.constraints.NotBlank;
import java.util.HashSet;
import java.util.Set;

/**
 * A named bundle of permissions.
 *
 * <p>The three built-in ones are seeded here so a grant can point at a row like any other, but they
 * are marked and refused an edit: {@link BuiltInRole} is what they mean, and a deployment that
 * redefined one would make every other deployment's documentation wrong.
 */
@Entity
@Table(
        name = "role_definition",
        indexes = {@Index(name = "idx_role_definition_name", columnList = "name", unique = true)})
public class RoleDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "role_definition_seq")
    public Long id;

    @Column(nullable = false, length = 100)
    @NotBlank
    public String name;

    @Column(length = 500)
    public String description;

    /** Whether this is one of the three that cannot be edited. */
    @Column(name = "is_built_in", nullable = false)
    public boolean builtIn = false;

    /**
     * What the role carries.
     *
     * <p>Eagerly fetched: a role is small, it is read on every permission check, and a lazy
     * collection on the reactive session is a fetch that has to be arranged rather than one that
     * happens.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(
            name = "role_permission",
            joinColumns = @JoinColumn(name = "role_id"),
            indexes = {@Index(name = "idx_role_permission_role", columnList = "role_id")})
    // The column definition is spelled out so Hibernate emits a plain varchar rather than a
    // varchar with a check constraint listing today's permission names. That constraint is
    // generated once and never widened by a schema update, so the first permission added
    // after a developer's database was created is one every insert is refused for — and the
    // only symptom is a seeder that logs and gives up. The migrations already declare this
    // column as a plain varchar, so this is also what makes dev and production agree.
    @Column(name = "permission", nullable = false, length = 64, columnDefinition = "varchar(64)")
    @Enumerated(EnumType.STRING)
    public Set<Permission> permissions = new HashSet<>();
}
