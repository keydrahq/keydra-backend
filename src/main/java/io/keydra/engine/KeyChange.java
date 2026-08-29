package io.keydra.engine;

/**
 * One key changing, as the store said it.
 *
 * <p>What arrives rather than what is true. A store's change notifications are advisory — this one
 * is fire-and-forget, dropped when a subscriber falls behind, and never sent at all for the writes
 * that happened while nobody was listening — so nothing above this may treat a {@code KeyChange} as
 * a record of what the keyspace holds. It says that something moved, which is enough to know that
 * what is on a screen is out of date.
 *
 * @param database which database it happened in, because a target has several and a browser is in
 *     one of them
 * @param key the key that changed
 * @param event what happened to it, in the store's own vocabulary — {@code set}, {@code del},
 *     {@code expired}, {@code rename_from}. Not translated: the words are the server's and
 *     inventing a second vocabulary would mean maintaining a mapping that silently drops whatever
 *     is new.
 */
public record KeyChange(int database, String key, String event) {}
