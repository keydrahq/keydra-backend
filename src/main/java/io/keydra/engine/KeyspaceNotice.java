package io.keydra.engine;

/**
 * Whether a target is telling anybody about its changes, and what it would take.
 *
 * @param setting the store's setting as it stands, for a page that wants to show it
 * @param delivers whether what it stands at actually delivers the changes a browser needs
 * @param wouldBecome what the setting would be set to in order to deliver them — the union of what
 *     is there and what is missing, never a replacement, because a value somebody chose was chosen
 *     for a reason and taking away events is not this feature's business
 */
public record KeyspaceNotice(String setting, boolean delivers, String wouldBecome) {}
