package io.keydra.authz.persistence;

import io.keydra.authz.entity.RecoveryCode;
import io.keydra.authz.entity.SecondFactor;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;

/** The rows behind a second factor: the pairing, and the codes that get somebody back in. */
@ApplicationScoped
public class SecondFactorRepository {

    public Uni<SecondFactor> forUser(Long userId) {
        return Panache.getSession().flatMap(session -> session.find(SecondFactor.class, userId));
    }

    /**
     * Starts a pairing, replacing any unconfirmed one.
     *
     * <p>Replacing rather than refusing: somebody who opened the page, did not scan, and came back
     * a week later should get a fresh secret rather than an error about one they never used. A
     * *confirmed* factor is not replaced here — turning one off is its own deliberate act.
     */
    public Uni<SecondFactor> begin(Long userId, String secret) {
        return forUser(userId)
                .flatMap(
                        existing ->
                                Panache.getSession()
                                        .flatMap(
                                                session -> {
                                                    if (existing != null) {
                                                        existing.secret = secret;
                                                        existing.createdAt = Instant.now();
                                                        existing.confirmedAt = null;
                                                        return Uni.createFrom().item(existing);
                                                    }
                                                    SecondFactor row = new SecondFactor();
                                                    row.userId = userId;
                                                    row.secret = secret;
                                                    return session.persist(row).replaceWith(row);
                                                }));
    }

    public Uni<Integer> remove(Long userId) {
        return execute("delete from SecondFactor where userId = :id", userId)
                .flatMap(removed -> removeCodes(userId).replaceWith(removed));
    }

    public Uni<Integer> removeCodes(Long userId) {
        return execute("delete from RecoveryCode where userId = :id", userId);
    }

    public Uni<Void> addCodes(Long userId, List<String> hashes) {
        return Panache.getSession()
                .flatMap(
                        session -> {
                            Uni<Void> chain = Uni.createFrom().voidItem();
                            for (String hash : hashes) {
                                RecoveryCode code = new RecoveryCode();
                                code.userId = userId;
                                code.codeHash = hash;
                                chain = chain.flatMap(ignored -> session.persist(code));
                            }
                            return chain;
                        });
    }

    /** Spends one code, if it is this person's and has not been spent. Answers whether it was. */
    public Uni<Boolean> spend(Long userId, String hash, Instant at) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "update RecoveryCode set usedAt = :at where userId"
                                                    + " = :id and codeHash = :hash and usedAt is"
                                                    + " null")
                                        .setParameter("at", at)
                                        .setParameter("id", userId)
                                        .setParameter("hash", hash)
                                        .executeUpdate())
                .map(changed -> changed > 0);
    }

    /** How many codes are left, which is what the settings page shows. */
    public Uni<Long> unusedCodeCount(Long userId) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "select count(*) from RecoveryCode where userId ="
                                                        + " :id and usedAt is null",
                                                Long.class)
                                        .setParameter("id", userId)
                                        .getSingleResult());
    }

    private Uni<Integer> execute(String query, Long userId) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(query)
                                        .setParameter("id", userId)
                                        .executeUpdate());
    }
}
