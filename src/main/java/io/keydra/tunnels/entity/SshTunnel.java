package io.keydra.tunnels.entity;

import io.keydra.connections.persistence.EncryptedStringConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

/**
 * A jump host, as a thing rather than as six fields on whatever needed it.
 *
 * <p>These were columns on a connection profile, on the reasoning that a tunnel exists to reach one
 * target and has no life apart from it. That was true for one target and false for twenty behind
 * the same jump host: twenty copies of one key, and rotating it means editing twenty profiles and
 * missing one. It was also the wrong shape for everything else that has to reach out — a backup
 * destination behind the same jump host could not use the tunnel that already existed.
 *
 * <p>So a jump host is configured once and pointed at. Which is the same move destinations made,
 * for the same reason.
 */
@Entity
@Table(
        name = "ssh_tunnel",
        indexes = {@Index(name = "idx_ssh_tunnel_name", columnList = "name", unique = true)})
public class SshTunnel {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "ssh_tunnel_seq")
    public Long id;

    @Column(nullable = false, length = 200)
    @NotBlank
    public String name;

    @Column(nullable = false, length = 255)
    @NotBlank
    public String host;

    @Column(name = "port_number", nullable = false)
    @Min(1)
    @Max(65535)
    public int port = 22;

    @Column(nullable = false, length = 200)
    @NotBlank
    public String username;

    /** Encrypted at rest; never returned by the API and never logged. */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2048)
    public String password;

    /**
     * A private key in PEM form, encrypted at rest.
     *
     * <p>Stored rather than referenced by path because Keydra may be running in a container with no
     * filesystem worth putting a key on, and a key on a shared volume is a key more people can read
     * than the one person who added it.
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "private_key", length = 8192)
    public String privateKey;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(length = 2048)
    public String passphrase;

    /**
     * The server key this jump host is expected to present, as {@code SHA256:…}.
     *
     * <p>Null means any key is accepted, which is what the tunnels did before this existed and what
     * makes a jump host impersonatable by anything that can answer on its address — the traffic
     * through it is every credential Keydra holds for everything behind it. It stays the default
     * only because refusing every existing tunnel on upgrade would be worse; the interface says so
     * plainly and the check offers the fingerprint it saw, so pinning one is a copy and a save.
     */
    @Column(name = "host_key_fingerprint", length = 128)
    public String hostKeyFingerprint;

    /** One line saying where this goes, for a list that has no room for the fields. */
    public String describedAs() {
        return username + "@" + host + (port == 22 ? "" : ":" + port);
    }

    public boolean hasPassword() {
        return password != null && !password.isBlank();
    }

    public boolean hasPrivateKey() {
        return privateKey != null && !privateKey.isBlank();
    }
}
