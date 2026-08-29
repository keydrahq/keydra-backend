package io.keydra.authz.persistence;

import io.keydra.authz.entity.SignInPolicy;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;

/** The one row that says what this installation asks of everybody who signs in. */
@ApplicationScoped
public class SignInPolicyRepository {

    /** The policy as it stands, or null when nobody has ever set one. */
    public Uni<SignInPolicy> read() {
        return Panache.getSession()
                .flatMap(session -> session.find(SignInPolicy.class, SignInPolicy.ONLY));
    }

    /**
     * Writes it, making the row if there is not one yet.
     *
     * <p>A find-then-write rather than an upsert in SQL, for the reason the preferences give:
     * Hibernate Reactive has no portable upsert. This one is written when somebody flips a switch
     * that changes what an installation asks of everybody, which is not a thing that happens twice
     * in a minute.
     */
    public Uni<SignInPolicy> write(boolean secondFactorRequired, String by, Instant at) {
        return read().flatMap(
                        existing -> {
                            if (existing != null) {
                                existing.secondFactorRequired = secondFactorRequired;
                                existing.changedAt = at;
                                existing.changedBy = by;
                                return Uni.createFrom().item(existing);
                            }
                            SignInPolicy row = new SignInPolicy();
                            row.secondFactorRequired = secondFactorRequired;
                            row.changedAt = at;
                            row.changedBy = by;
                            return Panache.getSession()
                                    .flatMap(session -> session.persist(row))
                                    .replaceWith(row);
                        });
    }

    /**
     * How many accounts would be restricted by turning the requirement on.
     *
     * <p>Only the accounts the requirement can reach: enabled, local, and with a password to sign
     * in with. An account that belongs to an identity provider proved who it was somewhere else,
     * and one that has been made but never claimed cannot sign in at all yet — counting either
     * would inflate the number the page exists to make honest.
     */
    public Uni<Long> owingAFactor() {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery(
                                                "select count(u) from AppUser u where u.enabled ="
                                                    + " true and u.provider = 'local' and"
                                                    + " u.passwordHash is not null and not exists"
                                                    + " (select f.userId from SecondFactor f where"
                                                    + " f.userId = u.id and f.confirmedAt is not"
                                                    + " null)",
                                                Long.class)
                                        .getSingleResult());
    }

    /** Used by the tests to start from nothing. */
    public Uni<Integer> deleteAll() {
        return Panache.getSession()
                .flatMap(
                        session -> session.createQuery("delete from SignInPolicy").executeUpdate());
    }
}
