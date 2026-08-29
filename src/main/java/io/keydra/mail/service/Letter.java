package io.keydra.mail.service;

/**
 * A message that is finished and only has to be sent.
 *
 * <p>Both bodies rather than one, because the mail goes out as {@code multipart/alternative}: the
 * text part is what a client that will not render HTML shows, and its absence is also what a spam
 * filter marks a message down for. An invitation in a junk folder fails in the way that is hardest
 * to diagnose — the administrator sees it sent and the person sees nothing.
 *
 * @param subject the line somebody reads before deciding to open anything
 * @param html the letter as it is meant to look
 * @param text the same letter for a client that will not draw it
 */
public record Letter(String subject, String html, String text) {}
