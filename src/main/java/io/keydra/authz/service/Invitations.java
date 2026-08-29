package io.keydra.authz.service;

import io.keydra.authz.entity.AccountInvitation;
import io.keydra.authz.entity.AppUser;
import io.keydra.authz.persistence.AuthzRepository;
import io.keydra.authz.persistence.InvitationRepository;
import io.keydra.mail.service.Letter;
import io.keydra.mail.service.Letterhead;
import io.keydra.mail.service.Mailer;
import io.keydra.preferences.service.PreferenceService;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Letting somebody choose their own password.
 *
 * <p>An administrator makes an account and Keydra invites the person. The account exists with no
 * password at all, which {@code LocalIdentities} already refuses to sign in; a link arrives by
 * mail, and whoever follows it chooses something only they know. Nobody else ever types it, which
 * is what makes the audit log able to answer "who did this" about anything that account does.
 *
 * <p>A password reset is the same thing from the other side and shares every line of this. A second
 * implementation would be a second place to get token handling wrong, and token handling is where
 * getting it wrong hands somebody an account.
 */
@ApplicationScoped
public class Invitations {

    private static final Logger LOG = Logger.getLogger(Invitations.class);

    /** What the interface calls the language preference, which is the row this reads. */
    private static final String LANGUAGE = "language";

    /** What a link is worth, and what became of the attempt to deliver it. */
    public record Issued(String token, boolean mailed, String address) {}

    /** Why a link cannot be redeemed, when it cannot. */
    public enum Refusal {
        /** No such link, or one whose account has since been deleted. */
        UNKNOWN,
        /** There was a link and its deadline has passed. */
        EXPIRED,
        /** There was a link and somebody has already used it. */
        USED
    }

    /** What a link is good for, answered before anybody types a password into a form. */
    public record Standing(
            boolean usable,
            Refusal refusal,
            String username,
            String displayName,
            AccountInvitation.Purpose purpose) {

        static Standing refused(Refusal why) {
            return new Standing(false, why, null, null, null);
        }
    }

    private final AuthzRepository users;
    private final InvitationRepository invitations;
    private final PasswordHasher hasher;
    private final Mailer mailer;
    private final Letterhead letterhead;
    private final PreferenceService preferences;
    private final PublicUrl publicUrl;
    private final AuthzCache cache;
    private final Sessions sessions;
    private final Duration validFor;

    @Inject
    Invitations(
            AuthzRepository users,
            InvitationRepository invitations,
            PasswordHasher hasher,
            Mailer mailer,
            Letterhead letterhead,
            PreferenceService preferences,
            PublicUrl publicUrl,
            AuthzCache cache,
            Sessions sessions,
            @ConfigProperty(name = "keydra.invitations.valid-for", defaultValue = "P7D")
                    Duration validFor) {
        this.users = users;
        this.invitations = invitations;
        this.hasher = hasher;
        this.mailer = mailer;
        this.letterhead = letterhead;
        this.preferences = preferences;
        this.publicUrl = publicUrl;
        this.cache = cache;
        this.sessions = sessions;
        this.validFor = validFor;
    }

    /**
     * Issues a link for an account and tries to send it.
     *
     * <p>Whatever the account already had is ended first, so a link somebody forwarded last week
     * stops working the moment a fresh one is asked for.
     *
     * <p>The token comes back to the caller as well as going out by mail, and that is deliberate
     * rather than careless: an instance with no relay configured must not be an instance where
     * nobody can be given an account. The REST layer decides who is allowed to see it — an
     * administrator who just made the account, and nobody else.
     */
    @WithTransaction
    public Uni<Issued> invite(Long userId, AccountInvitation.Purpose purpose, String issuedBy) {
        Instant now = Instant.now();
        String token = InvitationTokens.issue();

        return users.user(userId)
                .flatMap(
                        user -> {
                            if (user == null) {
                                return Uni.createFrom()
                                        .failure(new IllegalArgumentException("No such account"));
                            }
                            AccountInvitation invitation = new AccountInvitation();
                            invitation.userId = userId;
                            invitation.tokenHash = InvitationTokens.fingerprint(token);
                            invitation.purpose = purpose;
                            invitation.createdAt = now;
                            invitation.createdBy = issuedBy;
                            invitation.expiresAt = now.plus(validFor);

                            return invitations
                                    .endLiveFor(userId, now)
                                    .flatMap(ignored -> invitations.save(invitation))
                                    .flatMap(saved -> deliver(user, token, purpose));
                        });
    }

    /**
     * Sends the link, and says whether it went.
     *
     * <p>An account with no address on it is not a failure either: the administrator gets the link
     * to pass on however they already pass things on.
     */
    private Uni<Issued> deliver(AppUser user, String token, AccountInvitation.Purpose purpose) {
        String address = user.email;
        // No address, no relay, or no idea what address a browser reaches this instance at.
        // The last one is why a link is not built from the request: mail goes to somebody who
        // is not here, and a link to whatever host this process is listening on is as often as
        // not a link they cannot open.
        if (address == null
                || address.isBlank()
                || !mailer.canSend()
                || !publicUrl.isConfigured()) {
            return Uni.createFrom().item(new Issued(token, false, address));
        }
        return preferences
                .forAccount(user.id, LANGUAGE)
                .map(letterhead::language)
                .flatMap(language -> post(user, token, purpose, address, language))
                .onFailure()
                .recoverWithItem(
                        failure -> {
                            LOG.debugf(failure, "Could not send an invitation");
                            return new Issued(token, false, address);
                        });
    }

    /**
     * The letter itself, once the language is known.
     *
     * <p>The address in the link carries that language, so the page it opens is in the language the
     * letter was written in. Its own parameter rather than the one the detector already reads:
     * {@code ?lng=} would be picked up and cached by the whole application, and what a letter was
     * written in should decide the page it leads to and nothing after it.
     */
    private Uni<Issued> post(
            AppUser user,
            String token,
            AccountInvitation.Purpose purpose,
            String address,
            String language) {
        String link =
                publicUrl.absolute("/invitation/" + token + "?lang=" + language).orElseThrow();
        Letter letter =
                letterhead.compose(
                        InvitationWording.draft(language, user, purpose, link, validFor));
        return mailer.send(address, letter).map(sent -> new Issued(token, sent, address));
    }

    /**
     * What a link is good for, without redeeming it.
     *
     * <p>Asked by the page before it shows a password form, so somebody who followed a link from an
     * old mail is told which of the three things happened rather than being given a form that will
     * refuse them after they have chosen a password.
     */
    @WithSession
    public Uni<Standing> standing(String token) {
        Instant now = Instant.now();
        return invitations
                .byFingerprint(InvitationTokens.fingerprint(token))
                .flatMap(
                        invitation -> {
                            if (invitation == null) {
                                return Uni.createFrom().item(Standing.refused(Refusal.UNKNOWN));
                            }
                            if (invitation.acceptedAt != null) {
                                return Uni.createFrom().item(Standing.refused(Refusal.USED));
                            }
                            if (!invitation.expiresAt.isAfter(now)) {
                                return Uni.createFrom().item(Standing.refused(Refusal.EXPIRED));
                            }
                            return users.user(invitation.userId)
                                    .map(
                                            user ->
                                                    user == null
                                                            ? Standing.refused(Refusal.UNKNOWN)
                                                            : new Standing(
                                                                    true,
                                                                    null,
                                                                    user.username,
                                                                    user.displayName,
                                                                    invitation.purpose));
                        });
    }

    /**
     * Redeems a link: sets the password, and spends the link doing it.
     *
     * <p>The link is marked used by a conditional update, and the number of rows it changed is what
     * decides whether the password is set. Two requests arriving together therefore cannot both
     * succeed — the second changes no rows and is refused — which is what "once" has to mean when
     * the thing being claimed is an account.
     */
    @WithTransaction
    public Uni<Standing> accept(String token, String password) {
        Instant now = Instant.now();
        return invitations
                .byFingerprint(InvitationTokens.fingerprint(token))
                .flatMap(
                        invitation -> {
                            if (invitation == null) {
                                return Uni.createFrom().item(Standing.refused(Refusal.UNKNOWN));
                            }
                            if (invitation.acceptedAt != null) {
                                return Uni.createFrom().item(Standing.refused(Refusal.USED));
                            }
                            if (!invitation.expiresAt.isAfter(now)) {
                                return Uni.createFrom().item(Standing.refused(Refusal.EXPIRED));
                            }
                            return invitations
                                    .markAccepted(invitation.id, now)
                                    .flatMap(
                                            claimed ->
                                                    claimed == 0
                                                            // Somebody else redeemed it between
                                                            // the read and the write.
                                                            ? Uni.createFrom()
                                                                    .item(
                                                                            Standing.refused(
                                                                                    Refusal.USED))
                                                            : setPassword(invitation, password));
                        });
    }

    private Uni<Standing> setPassword(AccountInvitation invitation, String password) {
        return users.user(invitation.userId)
                .flatMap(
                        user -> {
                            if (user == null) {
                                return Uni.createFrom().item(Standing.refused(Refusal.UNKNOWN));
                            }
                            user.passwordHash = hasher.hash(password);
                            // An invited account is enabled by the act of claiming it: it was
                            // made in advance of somebody arriving, and they have arrived.
                            user.enabled = true;
                            return users.save(user)
                                    // What may be signed in with has changed, and the cached
                                    // "there is no password here" must not outlive it.
                                    .call(ignored -> cache.forgetEverything())
                                    // Every session this account had ends with the password
                                    // that opened it. Somebody resetting a password they think
                                    // was taken is asking exactly for this, and a reset that
                                    // left the thief signed in would answer the wrong question.
                                    .call(saved -> sessions.endOthers(saved.id, null))
                                    .map(
                                            saved ->
                                                    new Standing(
                                                            true,
                                                            null,
                                                            saved.username,
                                                            saved.displayName,
                                                            invitation.purpose));
                        });
    }

    /**
     * Starts a reset for whoever owns an address, and says nothing about whether anybody does.
     *
     * <p>The answer is the same sentence either way. Anything else is a way to ask Keydra who has
     * an account here, which is a question a login page must not answer.
     */
    @WithTransaction
    public Uni<Void> requestReset(String usernameOrEmail) {
        return users.userByUsername(usernameOrEmail)
                .flatMap(
                        user -> {
                            if (user == null || !user.enabled || !"local".equals(user.provider)) {
                                // Nothing to do, and nothing said about why.
                                return Uni.createFrom().voidItem();
                            }
                            return invite(user.id, AccountInvitation.Purpose.RESET, user.username)
                                    .replaceWithVoid();
                        });
    }

    /** How long a link lasts, for the page that has to say so. */
    public Duration validFor() {
        return validFor;
    }

    /** Whether this instance can send the link itself, or has to hand it over. */
    public Optional<String> relayAddress() {
        return mailer.fromAddress();
    }
}
