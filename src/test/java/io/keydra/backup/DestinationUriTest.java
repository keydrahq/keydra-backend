package io.keydra.backup;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.keydra.backup.entity.BackupDestination;
import io.keydra.backup.entity.DestinationKind;
import io.keydra.backup.exception.BackupFailedException;
import io.keydra.backup.store.DestinationUri;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * The string that decides where a backup goes.
 *
 * <p>Worth its own test because it is the whole of what a kind means now: everything above deals in
 * destinations and file names, everything below is Camel, and this is the one place the two meet. A
 * wrong option here is a backup that silently lands in the wrong place or a connection that fails
 * for a reason nobody can read off the row.
 */
@QuarkusTest
class DestinationUriTest {

    @Inject DestinationUri uris;

    @Test
    void aBucketBecomesAnS3Endpoint() {
        BackupDestination destination = destination(DestinationKind.S3);
        destination.location = "nightly-backups";
        destination.path = "keydra/";
        destination.region = "eu-central-1";
        destination.accessKey = "AKIAEXAMPLE";
        destination.secretKey = "s3cr3t";

        String uri = uris.of(destination);

        assertThat(uri, startsWith("aws2-s3://nightly-backups?"));
        assertThat(uri, containsString("region=eu-central-1"));
        // RAW, so a secret containing a percent sign is not URL-decoded into something else.
        assertThat(uri, containsString("secretKey=RAW(s3cr3t)"));
        // The prefix belongs to the keys rather than to the endpoint, which is how a bucket
        // can hold more than one destination's backups.
        assertThat(DestinationUri.prefixOf(destination), equalTo("keydra/"));
    }

    @Test
    void aBucketWithNoKeyUsesWhateverTheMachineHas() {
        BackupDestination destination = destination(DestinationKind.S3);
        destination.location = "nightly-backups";

        String uri = uris.of(destination);

        // An instance role or a mounted service account, which a deployment that has one
        // should not have to paste a long-lived key to use.
        assertThat(uri, containsString("useDefaultCredentialsProvider=true"));
        assertThat(uri, not(containsString("accessKey")));
    }

    @Test
    void anS3CompatibleStoreGetsItsEndpointAndPathStyle() {
        BackupDestination destination = destination(DestinationKind.S3);
        destination.location = "backups";
        destination.endpoint = "http://10.0.0.4:9000";
        destination.pathStyle = true;

        String uri = uris.of(destination);

        assertThat(uri, containsString("overrideEndpoint=true"));
        assertThat(uri, containsString("uriEndpointOverride=http://10.0.0.4:9000"));
        // Without this a MinIO on an IP address is looked up as backups.10.0.0.4, and the
        // failure reads as a DNS problem rather than as a setting.
        assertThat(uri, containsString("forcePathStyle=true"));
    }

    @Test
    void anSftpDestinationCarriesItsUserHostAndDirectory() {
        BackupDestination destination = destination(DestinationKind.SFTP);
        destination.location = "backup.internal";
        destination.port = 2222;
        destination.accessKey = "keydra";
        destination.secretKey = "hunter2";
        destination.path = "/srv/backups";

        String uri = uris.of(destination);

        assertThat(uri, startsWith("sftp://keydra@backup.internal:2222/srv/backups?"));
        assertThat(uri, containsString("password=RAW(hunter2)"));
        // One connection for the whole path: stepwise fails against servers that do not allow
        // a relative cd, and costs a round trip per path element on the ones that do.
        assertThat(uri, containsString("stepwise=false"));
        assertThat(uri, containsString("streamDownload=true"));
    }

    @Test
    void anFtpDestinationIsBinaryAndPassive() {
        BackupDestination destination = destination(DestinationKind.FTP);
        destination.location = "ftp.internal";
        destination.accessKey = "keydra";

        String uri = uris.of(destination);

        assertThat(uri, startsWith("ftp://keydra@ftp.internal:21"));
        // Text mode corrupts a compressed file by rewriting line endings inside it, and
        // active mode asks the server to open a connection back through the firewall.
        assertThat(uri, containsString("binary=true"));
        assertThat(uri, containsString("passiveMode=true"));
    }

    @Test
    void anFtpDestinationWithTlsSpeaksFtps() {
        BackupDestination destination = destination(DestinationKind.FTP);
        destination.location = "ftp.internal";
        destination.accessKey = "keydra";
        destination.tls = true;

        assertThat(uris.of(destination), startsWith("ftps://"));
    }

    @Test
    void aLocalDestinationCannotPointOutsideItsRoot() {
        BackupDestination destination = destination(DestinationKind.LOCAL);
        destination.path = "../../etc";

        // A path in a row is a path somebody can point at /etc, and a backup job runs with the
        // application's own hands.
        assertThrows(BackupFailedException.class, () -> uris.of(destination));
    }

    @Test
    void aCustomDestinationIsItsOwnAddress() {
        BackupDestination destination = destination(DestinationKind.CUSTOM);
        destination.location = "azure-storage-blob://account/container?operation=uploadBlockBlob";

        assertThat(uris.of(destination), equalTo(destination.location));
        // Write-only, and it says so rather than failing at the first retention run.
        assertThat(DestinationUri.readable(destination), equalTo(false));
    }

    @Test
    void somethingThatIsNotAnAddressIsRefused() {
        BackupDestination destination = destination(DestinationKind.CUSTOM);
        destination.location = "just some words";

        assertThrows(BackupFailedException.class, () -> uris.of(destination));
    }

    @Test
    void anAzureContainerCarriesItsAccountAndCredentialType() {
        BackupDestination destination = destination(DestinationKind.AZURE_BLOB);
        destination.accessKey = "keydrabackups";
        destination.location = "nightly";
        destination.secretKey = "an-account-key";

        String uri = uris.of(destination);

        assertThat(uri, startsWith("azure-storage-blob://keydrabackups/nightly?"));
        assertThat(uri, containsString("accessKey=RAW(an-account-key)"));
        // Said explicitly: the component's default looks for the environment's own Azure
        // identity, which is right inside Azure and silent everywhere else.
        assertThat(uri, containsString("credentialType=SHARED_ACCOUNT_KEY"));
    }

    @Test
    void anAzureContainerWithNoKeyUsesTheMachinesIdentity() {
        BackupDestination destination = destination(DestinationKind.AZURE_BLOB);
        destination.accessKey = "keydrabackups";
        destination.location = "nightly";

        assertThat(uris.of(destination), containsString("credentialType=AZURE_IDENTITY"));
    }

    @Test
    void aGoogleBucketWithNoKeyUsesWhateverTheMachineHas() {
        BackupDestination destination = destination(DestinationKind.GCS);
        destination.location = "nightly-backups";

        String uri = uris.of(destination);

        assertThat(uri, startsWith("google-storage://nightly-backups?"));
        // The key is a JSON document rather than a password, so it is never in the address.
        assertThat(uri, not(containsString("serviceAccountKey")));
    }

    @Test
    void aGoogleKeyThatIsNotAKeyIsRefusedWhereSomebodyCanSeeIt() {
        BackupDestination destination = destination(DestinationKind.GCS);
        destination.location = "nightly-backups";
        destination.secretKey = "not a service account key";

        assertThrows(BackupFailedException.class, () -> uris.of(destination));
    }

    private static BackupDestination destination(DestinationKind kind) {
        BackupDestination destination = new BackupDestination();
        destination.id = 1L;
        destination.name = "test";
        destination.kind = kind;
        return destination;
    }
}
