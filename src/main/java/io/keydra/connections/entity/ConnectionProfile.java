package io.keydra.connections.entity;

import io.keydra.connections.persistence.EncryptedStringConverter;
import io.keydra.engine.EngineType;
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
import jakarta.persistence.Transient;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * A saved target definition.
 *
 * <p>A plain JPA entity: persistence lives in {@code ConnectionProfileRepository} rather than in
 * static methods on the entity itself. That keeps the entity a data model with one job, and avoids
 * the active-record footgun where a call has to be written a particular way for Panache's
 * build-time enhancement to rewrite it.
 *
 * <p>Never leaves the service layer — resources return DTO records, so the password cannot be
 * serialised by accident.
 */
@Entity
@Table(
        name = "connection_profile",
        indexes = {@Index(name = "idx_connection_profile_name", columnList = "name")})
public class ConnectionProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "connection_profile_seq")
    public Long id;

    @Column(nullable = false, unique = true)
    @NotBlank
    public String name;

    @Column(nullable = false)
    @NotBlank
    public String host;

    @Column(nullable = false)
    @Min(1)
    @Max(65535)
    public int port = 6379;

    public String username;

    /** Encrypted at rest; never returned by the API and never logged. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2048)
    public String password;

    @Column(nullable = false)
    public boolean tls = false;

    /**
     * The authority to trust for this target, as PEM, or null for the JVM's own store.
     *
     * <p>Not a secret: a certificate authority's certificate is the public half of the thing, and
     * it is returned by the API like any other field. What makes it worth having here rather than
     * in the JVM's trust store is scope — what is trusted for this target is trusted for this
     * target, and adding an authority for one server should not change what every other connection
     * this process makes will accept.
     */
    @Column(name = "tls_ca_cert", columnDefinition = "text")
    public String tlsCaCert;

    /** The certificate this instance presents when a target asks for one. Public, like any. */
    @Column(name = "tls_client_cert", columnDefinition = "text")
    public String tlsClientCert;

    /**
     * Its private half, protected by a passphrase or not.
     *
     * <p>Encrypted at rest, never returned and never logged — the treatment {@code
     * ssh_tunnel.private_key} gets, because it is the same kind of thing.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "tls_client_key", columnDefinition = "text")
    public String tlsClientKey;

    /**
     * What opens that key, where it is locked.
     *
     * <p>Phases 51 and 54 refused a protected key because Vert.x's PEM options have no field for a
     * passphrase. They still have none and no longer need one: the key is unlocked in {@code
     * common.tls.Certificates} before any client sees it, so what reaches Vert.x and what reaches
     * Aerospike is the same unencrypted key and neither knows this field exists.
     *
     * <p>A secret like the key it opens, and held the same way.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "tls_client_key_passphrase", length = 2048)
    public String tlsClientKeyPassphrase;

    /**
     * Whether an operation that could empty this target has to name it first.
     *
     * <p>Off by default, and not inferred from anything. A target is not production because its
     * name contains "prod", and guessing would be wrong in both directions — a scratch server
     * somebody learns to type through, and the one that matters because it is called {@code
     * cache-07}.
     */
    @Column(name = "guarded", nullable = false)
    public boolean guarded = false;

    /**
     * Whether an operation that could empty this target waits for a second person.
     *
     * <p>Beside {@link #guarded} rather than inside it. Naming a target answers "is this the server
     * I think it is"; this answers "should this happen at all", and the second question is not
     * settled by the first — somebody can be entirely certain which server they are looking at and
     * still be about to empty it on a Friday afternoon.
     *
     * <p>Off by default and never inferred, for the reason {@link #guarded} is: turning it on for
     * every existing target would stop every automation that has ever called these endpoints.
     */
    @Column(name = "requires_approval", nullable = false)
    public boolean requiresApproval = false;

    /**
     * The commands the console may run here that it refuses everywhere else, comma separated.
     *
     * <p>A column rather than a table of its own: what it holds is a short list of names chosen
     * from a fixed set, read on every command the console runs, and a join table would be a query
     * per command for something that fits in a column.
     *
     * <p>Only the half of the deny-list that is about the target can be named. The other half —
     * commands that would break Keydra's own pooled connection — is refused when this is saved and
     * refused again when a command is checked, because a row can predate the check.
     */
    @Column(name = "console_allowed", length = 1024)
    public String consoleAllowed;

    @Column(name = "db_index", nullable = false)
    @Min(0)
    public int database = 0;

    /**
     * The database this request is working in, when it is not the profile's own.
     *
     * <p>Not persisted: the saved profile says where a target opens, and browsing db 3 for a minute
     * is not a change to that. Carried on the profile rather than threaded through every engine
     * method because it is part of what identifies a connection — a RESP client is bound to one
     * database, so two databases are two clients, and the pool keys on both.
     */
    @Transient public Integer selectedDatabase;

    /** The database to actually talk to: the one chosen for this request, or the saved one. */
    public int effectiveDatabase() {
        return selectedDatabase == null ? database : selectedDatabase;
    }

    /**
     * Which backing store this profile talks to.
     *
     * <p>Stored rather than inferred so a future non-RESP store is a matter of configuration; the
     * flavour within a protocol (Redis versus Valkey) is still detected at runtime.
     *
     * <p>The column definition is spelled out so no check constraint is generated listing today's
     * engines: that list is never widened by a schema update, and adding a store would make every
     * profile refuse to save on a database that already existed.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, columnDefinition = "varchar(16)")
    @NotNull
    public EngineType engine = EngineType.RESP;

    /**
     * Which server this profile is expected to be pointing at.
     *
     * <p>The protocol above says how to talk; this says what is listening. It is what the catalog
     * draws before anything has answered — a target nobody has reached yet still belongs to a
     * server somebody chose — and the server's own answer replaces it the moment there is one.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @NotNull
    public ServerFlavor flavor = ServerFlavor.UNKNOWN;

    /**
     * Topology within the engine's protocol.
     *
     * <p>Column name left alone deliberately: renaming it would be a schema migration for no gain,
     * and Hibernate's dev-time update cannot rename a column — it would leave the old one behind,
     * not-null and unpopulated.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    @NotNull
    public ConnectionType type = ConnectionType.STANDALONE;

    /** Required when {@link #type} is {@link ConnectionType#SENTINEL}. */
    public String sentinelMasterName;

    /**
     * The Aerospike namespace this profile points at, and nothing to a RESP target.
     *
     * <p>Beside {@link #sentinelMasterName} and for the same reason: a field one arrangement needs
     * and the others have no use for. What {@link #database} is to a RESP store, this is to an
     * Aerospike one — except that a namespace is named rather than numbered, which is why it could
     * not simply reuse that column.
     */
    public String namespace;

    @Column(length = 2000)
    public String notes;

    /**
     * The jump host this target is reached through, if any.
     *
     * <p>These were six columns here, on the reasoning that a tunnel exists to reach one target and
     * has no life apart from it. True for one target and false for twenty behind the same jump host
     * — twenty copies of one key, and rotating it means editing twenty profiles and missing one. It
     * is a row now, and this points at it.
     *
     * <p>An id rather than an association: the pool that opens the tunnel needs the profile
     * detached and a lazy reference would be a fetch on a session that has already closed.
     */
    @Column(name = "tunnel_id")
    public Long tunnelId;

    public boolean hasCredentials() {
        return password != null && !password.isBlank();
    }
}
