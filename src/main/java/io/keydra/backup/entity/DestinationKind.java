package io.keydra.backup.entity;

/**
 * The kinds of place a backup can be sent.
 *
 * <p>In the order they earn their place, which is also the order of how often somebody already has
 * one. Adding another is adding a {@code BackupStore} implementation and a value here — the same
 * shape as adding an engine, and for the same reason: everything above the interface deals in files
 * and names, never in buckets or sessions.
 */
public enum DestinationKind {

    /**
     * A directory on the machine Keydra runs on.
     *
     * <p>Kept, and kept first, because it is the one that needs no credentials and because a
     * mounted share is a directory. It is also the honest answer to what phase 10 did, now behind
     * the same interface as the rest rather than being the only thing there is.
     */
    LOCAL,

    /** S3, and everything that speaks its API: MinIO, Cloudflare R2, DigitalOcean Spaces. */
    S3,

    /** Azure Blob Storage. */
    AZURE_BLOB,

    /** Google Cloud Storage. */
    GCS,

    /** SFTP, over SSH. */
    SFTP,

    /** FTP, and FTPS where the server offers it. */
    FTP,

    /**
     * Anywhere else, named by an endpoint address.
     *
     * <p>The reason the whole of this is built on components rather than on clients. The six above
     * are the ones with a form that knows which fields to ask for; this one is every other
     * component on the classpath and every component a dependency line could add — a JMS queue, an
     * HTTP endpoint, a mail server, a message broker.
     *
     * <p>Write-only, and it says so. Which addresses can also be listed and fetched from is a
     * property of each component, and an address that is arbitrary cannot be promised more than the
     * one operation every one of them has. That is a real limit, stated rather than discovered.
     */
    CUSTOM
}
