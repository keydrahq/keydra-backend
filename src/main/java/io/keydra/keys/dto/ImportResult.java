package io.keydra.keys.dto;

/**
 * What an import did.
 *
 * <p>Three numbers rather than one: a key skipped because it already existed and a key that the
 * store refused are different outcomes, and an import that reports only "42 written" hides which of
 * the two happened to the rest.
 *
 * @param restored keys written
 * @param skipped keys already present, left alone because replacing was not asked for
 * @param failed keys the store refused — a payload from a newer store, or a corrupt file
 * @param reason what the store said about the first refusal, null when nothing failed. A count on
 *     its own leaves the commonest case unexplained: a file taken from a newer server restores
 *     nowhere, and "4 failed" does not say that while "DUMP payload version or checksum are wrong"
 *     does.
 */
public record ImportResult(long restored, long skipped, long failed, String reason) {}
