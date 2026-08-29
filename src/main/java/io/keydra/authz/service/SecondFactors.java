package io.keydra.authz.service;

import io.keydra.authz.entity.SecondFactor;
import io.keydra.authz.persistence.SecondFactorRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

/**
 * Pairing an authenticator, and asking it afterwards.
 *
 * <p>The order matters and is the whole of what stops somebody locking themselves out: a secret is
 * generated and shown, and it does nothing at all until one correct code proves the pairing worked.
 * Until then a sign-in is a sign-in. That is why {@link SecondFactor#confirmedAt} exists rather
 * than a boolean written at the same moment as the secret.
 *
 * <p>Recovery codes come with the confirmation and not before it, because they are what makes a
 * lost phone survivable and there is nothing to survive until the factor is on.
 */
@ApplicationScoped
public class SecondFactors {

    /** How many recovery codes a confirmation hands out. */
    private static final int RECOVERY_CODES = 10;

    /**
     * How many bytes are behind one recovery code.
     *
     * <p>Ten, which is eighty bits, which is not guessable and is still short enough to read off a
     * screen and type. Formatted as two groups so somebody can keep their place.
     */
    private static final int RECOVERY_BYTES = 10;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecondFactorRepository repository;

    @Inject
    SecondFactors(SecondFactorRepository repository) {
        this.repository = repository;
    }

    /** Whether this account must show a code, which is the only question the sign-in path asks. */
    @WithSession
    public Uni<Boolean> isRequiredFor(Long userId) {
        return userId == null
                ? Uni.createFrom().item(false)
                : repository.forUser(userId).map(factor -> factor != null && factor.isConfirmed());
    }

    /**
     * Begins a pairing and answers the secret, once.
     *
     * <p>The only time the secret leaves the server. Everything after this is a code.
     */
    @WithTransaction
    public Uni<String> begin(Long userId) {
        String secret = Base32.newSecret();
        return repository.begin(userId, secret).replaceWith(secret);
    }

    /**
     * Proves the pairing, turns the factor on, and hands out the recovery codes.
     *
     * <p>Answers an empty list when the code was wrong, which the caller turns into a refusal. The
     * codes are returned once and stored only as hashes: nothing here can show them again, and an
     * administrator reading the table learns nothing.
     */
    @WithTransaction
    // On the method rather than on the class, which is the exception to the rule @ChangesAccess
    // documents. Most of what this class writes is a sign-in — accepting a code, spending a
    // recovery code — and clearing every cached identity and closing every socket on each of those
    // would be a stampede Keydra inflicted on itself. Confirming a factor and turning one off are
    // the two writes here that change what somebody may do, because on an installation that
    // requires one they are what stands between an account and its roles.
    @ChangesAccess
    public Uni<List<String>> confirm(Long userId, String code, Instant at) {
        return repository
                .forUser(userId)
                .flatMap(
                        factor -> {
                            if (factor == null || !Totp.matches(factor.secret, code, at)) {
                                return Uni.createFrom().item(List.of());
                            }
                            factor.confirmedAt = at;
                            return replaceRecoveryCodes(userId);
                        });
    }

    /** A fresh set, which invalidates every code the old set held. */
    @WithTransaction
    public Uni<List<String>> regenerateRecoveryCodes(Long userId) {
        return repository
                .forUser(userId)
                .flatMap(
                        factor ->
                                factor == null || !factor.isConfirmed()
                                        ? Uni.createFrom().item(List.of())
                                        : replaceRecoveryCodes(userId));
    }

    /** Turns it off, and forgets the codes with it. */
    @WithTransaction
    @ChangesAccess
    public Uni<Boolean> disable(Long userId) {
        return repository.remove(userId).map(removed -> removed > 0);
    }

    /** How many codes are left, so somebody can be told before they run out. */
    @WithSession
    public Uni<Long> recoveryCodesLeft(Long userId) {
        return repository.unusedCodeCount(userId);
    }

    /**
     * Whether this is the code, or one of the recovery codes.
     *
     * <p>Both, in that order, and a recovery code is spent by being accepted. The order is not
     * arbitrary: the ordinary case is an authenticator, and a recovery code should not be consumed
     * by somebody who mistyped six digits.
     */
    @WithTransaction
    public Uni<Boolean> accepts(Long userId, String code, Instant at) {
        return repository
                .forUser(userId)
                .flatMap(
                        factor -> {
                            if (factor == null || !factor.isConfirmed()) {
                                // Nothing to check against, so nothing to refuse. This has to come
                                // before the code is looked at: an account with no second factor
                                // posts no code, and refusing an absent code would refuse
                                // everybody. It did, once.
                                return Uni.createFrom().item(true);
                            }
                            if (code == null || code.isBlank()) {
                                return Uni.createFrom().item(false);
                            }
                            if (Totp.matches(factor.secret, code, at)) {
                                return Uni.createFrom().item(true);
                            }
                            return repository.spend(userId, hash(normalise(code)), at);
                        });
    }

    /** A recovery code as it is stored: SHA-256 of the normalised text, hex. */
    static String hash(String code) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(
                                            code.getBytes(
                                                    java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("This JVM cannot compute SHA-256", impossible);
        }
    }

    /**
     * Lower case, no spaces, no dashes — so how somebody typed it does not decide whether it works.
     */
    static String normalise(String code) {
        return code.replace("-", "").replace(" ", "").toLowerCase(Locale.ROOT);
    }

    private Uni<List<String>> replaceRecoveryCodes(Long userId) {
        List<String> codes = new ArrayList<>(RECOVERY_CODES);
        List<String> hashes = new ArrayList<>(RECOVERY_CODES);
        for (int i = 0; i < RECOVERY_CODES; i++) {
            String code = newRecoveryCode();
            codes.add(code);
            hashes.add(hash(normalise(code)));
        }
        return repository
                .removeCodes(userId)
                .flatMap(ignored -> repository.addCodes(userId, hashes))
                .replaceWith(codes);
    }

    /**
     * Hex in two groups, because a run of twenty characters is a run somebody loses their place in.
     */
    private static String newRecoveryCode() {
        byte[] bytes = new byte[RECOVERY_BYTES];
        RANDOM.nextBytes(bytes);
        String hex = HexFormat.of().formatHex(bytes);
        return hex.substring(0, 10) + "-" + hex.substring(10);
    }
}
