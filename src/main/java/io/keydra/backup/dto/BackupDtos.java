package io.keydra.backup.dto;

import io.keydra.backup.entity.DestinationKind;
import io.keydra.backup.service.BackupFile;
import io.keydra.backup.service.BackupHeader;
import io.keydra.backup.store.StoredBackup;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** The wire shapes for backups and the places they are sent. */
public final class BackupDtos {

    private BackupDtos() {}

    /**
     * A destination as somebody reading the list sees it.
     *
     * <p>No secret of any kind. {@code hasSecret} and {@code hasPrivateKey} say whether one is
     * stored, which is everything an interface needs to draw the difference between "not
     * configured" and "configured, and not being shown to you".
     */
    @Schema(name = "DestinationSummary", description = "Somewhere backups are sent")
    public record DestinationSummary(
            Long id,
            String name,
            DestinationKind kind,
            boolean enabled,
            String location,
            String path,
            Integer port,
            String endpoint,
            String region,
            boolean pathStyle,
            String accessKey,
            boolean hasSecret,
            boolean hasPrivateKey,
            boolean tls,
            /** The jump host it is reached through, if any. */
            Long tunnelId,
            /** Whether backups sent here are encrypted. The passphrase itself never leaves. */
            boolean encrypts,
            /**
             * The keys backups are encrypted to, when that is how it is done.
             *
             * <p>Returned, unlike every other field near it, because these are the halves that only
             * encrypt: showing them is how somebody checks the right keys are configured. Empty
             * when this destination does not encrypt to keys.
             */
            List<RecipientSummary> recipients,
            /** One line saying where this points, for a list that has no room for the fields. */
            String describedAs) {}

    /**
     * A destination to create or change.
     *
     * <p>An absent secret leaves the stored one alone; an empty one clears it. The same rule as a
     * target's password, and for the same reason: nothing can read a secret back to prefill it, and
     * treating absent as "clear" would wipe the credential every time a label was corrected.
     */
    @Schema(name = "DestinationRequest", description = "A destination to create or change")
    public record DestinationRequest(
            @NotBlank String name,
            @NotNull DestinationKind kind,
            Boolean enabled,
            String location,
            String path,
            Integer port,
            String endpoint,
            String region,
            Boolean pathStyle,
            String accessKey,
            String secretKey,
            String privateKey,
            String passphrase,
            Boolean tls,
            Long tunnelId,
            @Schema(description = "Write-only; never returned. Lose it and the backups are gone.")
                    String encryptionPassphrase,
            @Schema(
                            description =
                                    "The public halves of the keys to encrypt to. Keydra never"
                                            + " holds the other half of any of them. Absent leaves"
                                            + " the stored list alone; empty clears it, which turns"
                                            + " encryption off for the next backup.")
                    @Valid
                    List<RecipientRequest> recipients) {}

    /**
     * One key a backup can be opened with.
     *
     * @param label what somebody calls it, which is the only thing distinguishing two keys in a
     *     list — the rest is forty characters of base64
     * @param publicKey the half that only encrypts
     */
    @Schema(name = "RecipientSummary", description = "One key a backup can be opened with")
    public record RecipientSummary(String label, String publicKey) {}

    /** One key to encrypt to. */
    @Schema(name = "RecipientRequest", description = "One key to encrypt to")
    public record RecipientRequest(
            @NotBlank @Size(max = 200) String label, @NotBlank @Size(max = 200) String publicKey) {}

    /** What a "test this destination" attempt found. */
    @Schema(name = "DestinationCheck", description = "Whether a destination works")
    public record DestinationCheck(boolean reachable, String message) {}

    /**
     * One backup already in a destination.
     *
     * @param header what the file says about itself, read only when asked for — a listing does not
     *     open files
     */
    @Schema(name = "BackupSummary", description = "A backup already in a destination")
    public record BackupSummary(
            String name, long size, Instant modifiedAt, boolean encrypted, BackupHeader header) {

        public static BackupSummary of(StoredBackup stored) {
            return new BackupSummary(
                    stored.name(),
                    stored.size(),
                    stored.modifiedAt(),
                    // Read off the name rather than by opening the file: a listing that had to
                    // fetch every backup to draw a column would be one nobody waits for.
                    stored.name().endsWith(BackupFile.ENCRYPTED_SUFFIX),
                    null);
        }
    }

    /** What taking a backup did. */
    @Schema(name = "BackupTaken", description = "A backup that was just written")
    public record BackupTaken(
            String name, long keys, long size, String destination, List<String> removed) {}

    /** What to restore, and how. */
    @Schema(name = "RestoreRequest", description = "A backup to put back")
    public record RestoreRequest(
            @NotNull Long destinationId,
            @NotBlank String name,
            boolean replace,
            /**
             * The private half, for a backup encrypted to a key.
             *
             * <p>Used for this one restore and kept nowhere: not stored, not logged, not returned.
             * The operator holds the key; Keydra borrows it for a minute.
             */
            @Schema(description = "Write-only; used once and never stored") String privateKey) {}

    /** A key pair, handed over once. */
    @Schema(name = "BackupKeyPair", description = "A key pair for encrypting backups")
    public record KeyPair(
            String publicKey,
            @Schema(
                            description =
                                    "Shown once and stored nowhere. Without it the backups"
                                            + " encrypted to its other half cannot be read.")
                    String privateKey) {}
}
