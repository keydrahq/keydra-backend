package io.keydra.security.service;

import io.keydra.connections.persistence.EncryptedStringConverter;
import io.keydra.security.dto.SecretRotationDtos.RotationResult;
import io.keydra.security.dto.SecretRotationDtos.RotationStatus;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;

/**
 * Moves every stored credential onto the key that writes now.
 *
 * <p>A key that cannot be rotated is a key nobody rotates, which after the first person leaves is
 * the same as not having one. What makes it possible is that the envelope names the key that wrote
 * it: more than one can be readable at once, so the new key is added beside the old one, everything
 * is moved at leisure, and only then is the old one taken out.
 *
 * <p>The move itself is two statements per column. Reading a value applies the converter and gives
 * the plaintext; writing it back applies the converter the other way and encrypts with whatever
 * writes now. Nothing here handles a key or a cipher — that all stays in one place, which is the
 * point.
 */
@ApplicationScoped
public class SecretRotation {

    /**
     * Every encrypted column, by the two names each has.
     *
     * @param entity the JPQL entity and field, for reading and writing through the converter
     * @param table the table and column, for counting which key wrote what — a question only the
     *     stored form can answer, and the converter's whole job is to hide that
     */
    private record Secret(String entity, String field, String table, String column) {}

    private static final List<Secret> SECRETS =
            List.of(
                    new Secret("ConnectionProfile", "password", "connection_profile", "password"),
                    new Secret(
                            "ConnectionProfile",
                            "tlsClientKey",
                            "connection_profile",
                            "tls_client_key"),
                    new Secret(
                            "ConnectionProfile",
                            "tlsClientKeyPassphrase",
                            "connection_profile",
                            "tls_client_key_passphrase"),
                    new Secret("SshTunnel", "password", "ssh_tunnel", "password"),
                    new Secret("SshTunnel", "privateKey", "ssh_tunnel", "private_key"),
                    new Secret("SshTunnel", "passphrase", "ssh_tunnel", "passphrase"),
                    new Secret(
                            "IdentityProviderConfig",
                            "clientSecret",
                            "identity_provider",
                            "client_secret"),
                    new Secret(
                            "BackupDestination", "secretKey", "backup_destination", "secret_key"),
                    new Secret(
                            "BackupDestination", "privateKey", "backup_destination", "private_key"),
                    new Secret(
                            "BackupDestination", "passphrase", "backup_destination", "passphrase"),
                    new Secret(
                            "BackupDestination",
                            "encryptionPassphrase",
                            "backup_destination",
                            "encryption_passphrase"),
                    // A webhook address is a credential — the token is in its path — which is
                    // why it is on this list beside the passwords rather than beside the names.
                    new Secret("AlertDelivery", "url", "alert_delivery", "url"),
                    new Secret("AlertDelivery", "headerValue", "alert_delivery", "header_value"),
                    new Secret("AlertDelivery", "password", "alert_delivery", "password"),
                    new Secret("AlertDelivery", "apiToken", "alert_delivery", "api_token"),
                    // The one credential here that is reversible by design: verifying a six-digit
                    // code means computing one, which means having the secret back. A rotation
                    // that skipped it would leave every paired authenticator unreadable under a
                    // key nobody has any more, and the first symptom would be everybody with a
                    // second factor being unable to sign in.
                    new Secret("SecondFactor", "secret", "user_second_factor", "secret"),
                    // Not a credential, and on this list for a different reason: what a pending
                    // operation holds is somebody's data. For an import it is the dumped values
                    // themselves and for a bulk delete a list of key names, which everything else
                    // in this application already treats as the contents of somebody's target. A
                    // column encrypted and left off this list is one under a key somebody is about
                    // to delete.
                    new Secret("ApprovalRequest", "payload", "approval_request", "payload"));

    /**
     * Every column this knows about, as {@code Entity.field}.
     *
     * <p>Exposed so a test can compare it with what the entities actually declare. A rotation that
     * silently skips a column leaves a credential under a key somebody is about to delete, and the
     * only symptom is a value that stops decrypting weeks later — which is exactly the omission
     * this list acquired the first time a new encrypted column was added.
     */
    public static List<String> covers() {
        return SECRETS.stream().map(secret -> secret.entity() + "." + secret.field()).toList();
    }

    /** How many stored secrets are on the current key, and how many are not. */
    @WithSession
    public Uni<RotationStatus> status() {
        String current = EncryptedStringConverter.currentKeyId();
        Uni<long[]> counted = Uni.createFrom().item(new long[2]);
        for (Secret secret : SECRETS) {
            counted =
                    counted.flatMap(
                            totals ->
                                    countOf(secret, current)
                                            .map(
                                                    pair -> {
                                                        totals[0] += pair[0];
                                                        totals[1] += pair[1];
                                                        return totals;
                                                    }));
        }
        return counted.map(totals -> new RotationStatus(current, totals[0], totals[1]));
    }

    /**
     * Re-encrypts everything with the key that writes now.
     *
     * <p>Everything, not only what is on an old key: a value already on the current key is
     * rewritten with a fresh initialisation vector, which costs one statement and means the
     * rotation has one behaviour rather than two.
     */
    @WithTransaction
    public Uni<RotationResult> rotate() {
        Uni<Long> moved = Uni.createFrom().item(0L);
        for (Secret secret : SECRETS) {
            moved = moved.flatMap(soFar -> rotate(secret).map(count -> soFar + count));
        }
        return moved.map(
                count -> new RotationResult(EncryptedStringConverter.currentKeyId(), count));
    }

    /**
     * One column.
     *
     * <p>Row by row rather than one bulk statement, because a bulk update cannot re-encrypt: the
     * ciphertext of every row differs, so there is no single value to set. Reading and writing each
     * one is what applies the converter in both directions.
     */
    private Uni<Long> rotate(Secret secret) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "select e.id, e."
                                                        + secret.field()
                                                        + " from "
                                                        + secret.entity()
                                                        + " e where e."
                                                        + secret.field()
                                                        + " is not null",
                                                Object[].class)
                                        .getResultList())
                .flatMap(
                        rows -> {
                            Uni<Long> chain = Uni.createFrom().item(0L);
                            for (Object[] row : rows) {
                                Long id = (Long) row[0];
                                String value = (String) row[1];
                                chain =
                                        chain.flatMap(
                                                soFar ->
                                                        write(secret, id, value)
                                                                .map(ignored -> soFar + 1));
                            }
                            return chain;
                        });
    }

    private Uni<Integer> write(Secret secret, Long id, String value) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "update "
                                                        + secret.entity()
                                                        + " e set e."
                                                        + secret.field()
                                                        + " = :value where e.id = :id")
                                        .setParameter("value", value)
                                        .setParameter("id", id)
                                        .executeUpdate());
    }

    /** How many of one column are on the current key, and how many are not. */
    private Uni<long[]> countOf(Secret secret, String currentKeyId) {
        // Native, because the question is about the stored form and the converter exists to
        // stop anything above it from seeing that.
        String sql =
                "select count(*) filter (where "
                        + secret.column()
                        + " like :current) as on_current, count(*) filter (where "
                        + secret.column()
                        + " not like :current) as elsewhere from "
                        + secret.table()
                        + " where "
                        + secret.column()
                        + " is not null";
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createNativeQuery(sql, Object[].class)
                                        .setParameter("current", "enc:v2:" + currentKeyId + ":%")
                                        .getSingleResult())
                .map(
                        row ->
                                new long[] {
                                    ((Number) row[0]).longValue(), ((Number) row[1]).longValue()
                                });
    }
}
