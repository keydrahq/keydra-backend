package io.keydra.authz.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * One attempt to sign in.
 *
 * <p>Written whether it worked or not, and written for usernames that do not exist. An attempt
 * against a name nobody holds is not noise — one source working through forty of them is the
 * clearest thing in this table, and it is invisible if only real accounts are recorded.
 *
 * <p>What is kept about where it came from is the network rather than the address, which is the
 * rule {@link UserSession} already follows and the reason is the same: enough for somebody to
 * recognise their own office, not enough to be a record of where they have been. The country is
 * resolved from the full address while the request is still in flight and the address is then
 * dropped, so an instance with no geography database keeps strictly less and works the same.
 */
@Entity
@Table(name = "sign_in_attempt")
public class SignInAttempt {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    /**
     * As it was typed.
     *
     * <p>Not resolved to an account and not normalised away, because half of what this table is for
     * is attempts against names that resolve to nothing.
     */
    @Column(nullable = false, length = 200)
    public String username;

    /** The account, when the name was one. Null for an attempt against a name nobody holds. */
    @Column(name = "user_id")
    public Long userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    public SignInOutcome outcome;

    /** {@code password}, or the key of the identity provider that vouched for them. */
    @Column(nullable = false, length = 64)
    public String method;

    @Column(name = "at", nullable = false)
    public Instant at = Instant.now();

    /** The address with its last part removed. See {@link UserSession#network}. */
    @Column(length = 64)
    public String network;

    /** Two letters, when this instance has a geography database. Null when it has none. */
    @Column(length = 2)
    public String country;

    @Column(name = "user_agent", length = 400)
    public String userAgent;

    /**
     * What was noticed about this sign-in, comma separated.
     *
     * <p>Stored as text rather than as rows in a second table. These are read together, written
     * once and never queried by one signal — a join would buy nothing and cost a table.
     */
    @Column(length = 400)
    public String anomalies;

    public Set<SignInAnomaly> anomalySet() {
        if (anomalies == null || anomalies.isBlank()) {
            return EnumSet.noneOf(SignInAnomaly.class);
        }
        return Arrays.stream(anomalies.split(","))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .map(SignInAnomaly::valueOf)
                .collect(Collectors.toCollection(() -> EnumSet.noneOf(SignInAnomaly.class)));
    }

    public void anomalySet(Set<SignInAnomaly> noticed) {
        anomalies =
                noticed.isEmpty()
                        ? null
                        : noticed.stream().map(Enum::name).collect(Collectors.joining(","));
    }
}
