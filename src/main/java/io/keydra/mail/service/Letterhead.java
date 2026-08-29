package io.keydra.mail.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * The frame every letter Keydra sends about itself is written on.
 *
 * <p>Here rather than in the domain that has something to say, so the two messages that exist today
 * and whatever is added next look like they came from the same place. What a domain writes is the
 * words — a heading, some paragraphs, one thing to do — and this turns them into a message a mail
 * client will actually draw.
 *
 * <p><b>Every declaration is inline.</b> HTML mail is not HTML: a {@code <style>} block is stripped
 * by Outlook.com and unreliable in Gmail, a {@code <div>} grid is laid out by Word in desktop
 * Outlook, and padding on an {@code <a>} is ignored there. So the layout is {@code <table
 * role="presentation">}, the button is a table cell with a background colour wrapping a padded
 * anchor, and nothing depends on a stylesheet arriving.
 *
 * <p>Two things follow from having no {@code <style>} block. There is no {@code
 * prefers-color-scheme} rule, so the letter does not declare {@code color-scheme} either — telling
 * a client that a dark theme is handled stops it applying its own inversion, and no inversion at
 * all is worse than a rough one. And there are no images: an SVG is stripped, a PNG needs hosting
 * and arrives blocked by default, so the mark is the word set in type under a red rule.
 */
@ApplicationScoped
public class Letterhead {

    /**
     * The languages a letter can be written in.
     *
     * <p>The same set the interface is translated to, and it has to stay that way: the letter's
     * link carries the language it was written in and the page it opens honours it, so a language
     * here that the browser has no bundle for is a Turkish letter leading to an English page.
     */
    public static final Set<String> LANGUAGES = Set.of("en", "tr");

    /** Keydra's red, the one part of the identity that is not the reader's theme to decide. */
    private static final String ACCENT = "#EE0000";

    private static final String INK = "#151515";
    private static final String QUIET = "#4d4d4d";
    private static final String FAINT = "#6a6e73";
    private static final String PAPER = "#ffffff";
    private static final String DESK = "#f2f2f2";
    private static final String RULE = "#d2d2d2";

    /**
     * What a mail client will find on the machine it is drawing on.
     *
     * <p>Ends in a generic family because desktop Outlook renders with Word, which silently
     * substitutes for a font it does not have and picks Times New Roman when the stack gives it
     * nothing better.
     */
    private static final String FACE =
            "-apple-system,BlinkMacSystemFont,'Segoe UI',Helvetica,Arial,sans-serif";

    private final String fallback;

    @Inject
    Letterhead(
            @ConfigProperty(name = "keydra.mail.language", defaultValue = "en") String fallback) {
        this.fallback = fallback;
    }

    /**
     * The words of one letter, before it is dressed.
     *
     * @param language which of {@link #LANGUAGES} it is written in
     * @param subject the line an inbox lists it under
     * @param preheader the sentence shown beside the subject in that list, and nowhere else
     * @param heading what the letter is about, said once at the top
     * @param paragraphs the body, one entry per paragraph
     * @param actionLabel what the button says
     * @param actionHref where it goes, printed as text underneath it as well
     * @param footnote the small print under the rule: how long the link lasts, and what to do if it
     *     was not expected
     * @param footer the line below the card saying who sent this
     */
    public record Draft(
            String language,
            String subject,
            String preheader,
            String heading,
            List<String> paragraphs,
            String actionLabel,
            String actionHref,
            String footnote,
            String footer) {}

    /** Which language to write in, given whatever is known about the reader. */
    public String language(String preferred) {
        if (preferred != null && LANGUAGES.contains(preferred.toLowerCase(Locale.ROOT))) {
            return preferred.toLowerCase(Locale.ROOT);
        }
        return LANGUAGES.contains(fallback) ? fallback : "en";
    }

    /** Turns the words into the two bodies that go on the wire. */
    public Letter compose(Draft draft) {
        return new Letter(draft.subject(), html(draft), text(draft));
    }

    // --- The letter as it is meant to look ---------------------------------

    private String html(Draft draft) {
        StringBuilder body = new StringBuilder();
        for (String paragraph : draft.paragraphs()) {
            body.append(row("16px 32px 0 32px", "16px", "24px", INK, escape(paragraph)));
        }
        body.append(button(draft.actionLabel(), draft.actionHref()));
        // The address as text under the button. Clients block links and proxies rewrite them, and
        // somebody reading on a machine that is not the one they will sign in on has to be able to
        // copy it out.
        body.append(
                """
                    <tr><td style="padding:16px 32px 0 32px;font-family:%s;font-size:13px;\
                line-height:20px;color:%s;word-break:break-all;">%s</td></tr>
                """
                        .formatted(FACE, QUIET, escape(draft.actionHref())));
        body.append(rule());
        body.append(row("16px 32px 32px 32px", "13px", "20px", QUIET, escape(draft.footnote())));

        return """
        <!DOCTYPE html>
        <html lang="%s">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <title>%s</title>
        </head>
        <body style="margin:0;padding:0;background-color:%s;">
        <div style="display:none;max-height:0;overflow:hidden;mso-hide:all;">%s</div>
        <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0"\
         style="background-color:%s;">
        <tr><td align="center" style="padding:32px 16px;">
        <table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0"\
         style="width:100%%;max-width:600px;background-color:%s;border:1px solid %s;\
        border-radius:8px;">
        <tr><td style="height:4px;line-height:4px;font-size:4px;background-color:%s;\
            border-radius:7px 7px 0 0;">&nbsp;</td></tr>
        <tr><td style="padding:28px 32px 0 32px;font-family:%s;font-size:18px;line-height:24px;\
        font-weight:700;letter-spacing:0.02em;color:%s;">Keydra</td></tr>
        <tr><td style="padding:20px 32px 0 32px;font-family:%s;font-size:24px;line-height:32px;\
        font-weight:600;color:%s;">%s</td></tr>
        %s</table>
        <table role="presentation" width="600" cellpadding="0" cellspacing="0" border="0"\
         style="width:100%%;max-width:600px;">
        <tr><td align="center" style="padding:20px 16px 0 16px;font-family:%s;font-size:12px;\
        line-height:18px;color:%s;">%s</td></tr>
        </table>
        </td></tr>
        </table>
        </body>
        </html>
        """
                .formatted(
                        escape(draft.language()),
                        escape(draft.subject()),
                        DESK,
                        escape(draft.preheader()),
                        DESK,
                        PAPER,
                        RULE,
                        ACCENT,
                        FACE,
                        INK,
                        FACE,
                        INK,
                        escape(draft.heading()),
                        body,
                        FACE,
                        FAINT,
                        escape(draft.footer()));
    }

    private static String row(
            String padding, String size, String leading, String colour, String content) {
        return """
            <tr><td style="padding:%s;font-family:%s;font-size:%s;line-height:%s;color:%s;">\
        %s</td></tr>
        """
                .formatted(padding, FACE, size, leading, colour, content);
    }

    /**
     * A button that survives Word.
     *
     * <p>The colour is on the cell and the padding is on the anchor: desktop Outlook ignores
     * padding on an {@code <a>} but honours a table cell's background, so between them the thing is
     * still a button there and everywhere else.
     */
    private static String button(String label, String href) {
        return """
            <tr><td style="padding:28px 32px 0 32px;">
            <table role="presentation" cellpadding="0" cellspacing="0" border="0"><tr>
            <td align="center" bgcolor="%s" style="background-color:%s;border-radius:6px;">
            <a href="%s" style="display:inline-block;padding:13px 28px;font-family:%s;\
        font-size:16px;line-height:20px;font-weight:600;color:%s;text-decoration:none;\
        border-radius:6px;">%s</a>
            </td></tr></table>
            </td></tr>
        """
                .formatted(ACCENT, ACCENT, escape(href), FACE, PAPER, escape(label));
    }

    private static String rule() {
        return """
            <tr><td style="padding:28px 32px 0 32px;">
            <table role="presentation" width="100%%" cellpadding="0" cellspacing="0" border="0">
            <tr><td style="height:1px;line-height:1px;font-size:1px;background-color:%s;">\
        &nbsp;</td></tr></table>
            </td></tr>
        """
                .formatted(RULE);
    }

    // --- The same letter for a client that will not draw it ----------------

    private static String text(Draft draft) {
        StringBuilder plain = new StringBuilder(draft.heading()).append("\n\n");
        for (String paragraph : draft.paragraphs()) {
            plain.append(paragraph).append("\n\n");
        }
        plain.append(draft.actionLabel()).append(":\n").append(draft.actionHref()).append("\n\n");
        plain.append(draft.footnote()).append("\n\n--\n").append(draft.footer()).append('\n');
        return plain.toString();
    }

    /**
     * What a display name is worth in markup.
     *
     * <p>Everything variable in a letter came out of the database — a name somebody typed, a
     * username, a link — and the one place it is pasted into markup is here. Attribute values and
     * text go through the same call: every attribute written above is double-quoted, so the four
     * characters below are the whole set for both.
     */
    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }
}
