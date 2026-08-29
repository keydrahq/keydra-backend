package io.keydra.engine;

/**
 * One database inside a target, and how much is in it.
 *
 * <p>A RESP server holds several numbered keyspaces that share a process and a memory limit but
 * nothing else — a key in one is invisible from another. The count is what makes the list worth
 * showing: sixteen identical numbers say nothing about where somebody's data actually is.
 *
 * @param index the number a client selects it by
 * @param keys how many keys it holds, or 0 for an empty one
 * @param expires how many of those keys have an expiry set
 */
public record Database(int index, long keys, long expires) {}
