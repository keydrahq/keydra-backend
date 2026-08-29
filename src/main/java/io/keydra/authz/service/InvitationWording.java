package io.keydra.authz.service;

import io.keydra.authz.entity.AccountInvitation;
import io.keydra.authz.entity.AppUser;
import io.keydra.mail.service.Letterhead;
import java.net.URI;
import java.time.Duration;
import java.util.List;

/**
 * What the two letters Keydra sends about itself actually say.
 *
 * <p>Here rather than inside {@link Invitations} because a service orchestrates and this writes:
 * the same split {@code AlertWording} makes next door. The difference is the language. An alert is
 * addressed at a channel and there is nobody to look a preference up for, so it stays English; an
 * invitation is addressed at one person whose language Keydra has known since phase 37.
 *
 * <p>A table per language rather than a resource bundle. The bundle would put four sentences in a
 * properties file where a missing key is a runtime surprise, and buy nothing back: these strings
 * are not translated by anybody who is not also reading this file, and a {@code switch} over the
 * language makes the compiler check that every case answers.
 */
final class InvitationWording {

    private InvitationWording() {}

    /** One letter, in one language, for one account. */
    static Letterhead.Draft draft(
            String language,
            AppUser user,
            AccountInvitation.Purpose purpose,
            String link,
            Duration validFor) {
        boolean reset = purpose == AccountInvitation.Purpose.RESET;
        String name =
                user.displayName == null || user.displayName.isBlank() ? null : user.displayName;
        return "tr".equals(language)
                ? turkish(user, name, reset, link, validFor)
                : english(user, name, reset, link, validFor);
    }

    private static Letterhead.Draft english(
            AppUser user, String name, boolean reset, String link, Duration validFor) {
        String greeting = name == null ? "Hello," : "Hello " + name + ",";
        List<String> paragraphs =
                reset
                        ? List.of(
                                greeting,
                                "Somebody asked to set a new password for your Keydra account ("
                                        + user.username
                                        + ").",
                                "Choosing one signs out every browser that is signed in as you"
                                        + " right now.")
                        : List.of(
                                greeting,
                                "An account has been made for you in Keydra, a console for Redis"
                                        + " and Valkey servers. Your username is "
                                        + user.username
                                        + ".",
                                "Choose a password to finish setting it up. Nobody else will know"
                                        + " it, including whoever made the account.");
        return new Letterhead.Draft(
                "en",
                reset ? "Set a new Keydra password" : "You have been given a Keydra account",
                reset
                        ? "Choose a new password for " + user.username + "."
                        : "Choose a password and the account is ready to use.",
                reset ? "Set a new password" : "Welcome to Keydra",
                paragraphs,
                reset ? "Set a new password" : "Choose a password",
                link,
                "The link works once and expires in "
                        + englishSpan(validFor)
                        + ". "
                        + (reset
                                ? "If you did not ask for this, nothing has happened and your"
                                        + " current password still works."
                                : "If you were not expecting this message, nothing has happened"
                                        + " and you can ignore it."),
                footer(link, "Sent by Keydra"));
    }

    private static Letterhead.Draft turkish(
            AppUser user, String name, boolean reset, String link, Duration validFor) {
        String greeting = name == null ? "Merhaba," : "Merhaba " + name + ",";
        List<String> paragraphs =
                reset
                        ? List.of(
                                greeting,
                                "Keydra hesabınız ("
                                        + user.username
                                        + ") için yeni bir parola belirlenmesi istendi.",
                                "Yeni parolayı belirlediğinizde, o anda hesabınızla açık olan"
                                        + " bütün oturumlar kapanır.")
                        : List.of(
                                greeting,
                                "Redis ve Valkey sunucuları için bir konsol olan Keydra'da sizin"
                                        + " adınıza bir hesap açıldı. Kullanıcı adınız: "
                                        + user.username
                                        + ".",
                                "Kurulumu tamamlamak için bir parola belirleyin. Bu parolayı,"
                                        + " hesabı açan kişi dahil başka kimse bilmeyecek.");
        return new Letterhead.Draft(
                "tr",
                reset ? "Keydra parolanızı yenileyin" : "Keydra hesabınız açıldı",
                reset
                        ? user.username + " için yeni bir parola belirleyin."
                        : "Bir parola belirleyin, hesap kullanıma hazır.",
                reset ? "Yeni bir parola belirleyin" : "Keydra'ya hoş geldiniz",
                paragraphs,
                reset ? "Yeni parola belirle" : "Parola belirle",
                link,
                "Bağlantı bir kez çalışır ve "
                        + turkishSpan(validFor)
                        + " sonra geçerliliğini yitirir. "
                        + (reset
                                ? "Bunu siz istemediyseniz hiçbir şey olmadı; mevcut parolanız"
                                        + " geçerli olmaya devam eder."
                                : "Bu iletiyi beklemiyorduysanız hiçbir şey olmadı, yok"
                                        + " sayabilirsiniz."),
                footer(link, "Keydra tarafından gönderildi"));
    }

    /**
     * Who sent this, and from which Keydra.
     *
     * <p>The host comes out of the link rather than being asked for separately: the link is already
     * the one address in the letter that has to be right, and somebody who uses two installations
     * needs the line under the card to say which one this is.
     */
    private static String footer(String link, String sender) {
        try {
            String host = URI.create(link).getHost();
            return host == null ? sender : sender + " · " + host;
        } catch (IllegalArgumentException notAnAddress) {
            return sender;
        }
    }

    /** How long the link lasts, in the largest unit that leaves a whole number. */
    private static String englishSpan(Duration validFor) {
        long hours = Math.max(1, validFor.toHours());
        if (hours % 24 == 0) {
            long days = hours / 24;
            return days == 1 ? "1 day" : days + " days";
        }
        return hours == 1 ? "1 hour" : hours + " hours";
    }

    private static String turkishSpan(Duration validFor) {
        long hours = Math.max(1, validFor.toHours());
        return hours % 24 == 0 ? (hours / 24) + " gün" : hours + " saat";
    }
}
