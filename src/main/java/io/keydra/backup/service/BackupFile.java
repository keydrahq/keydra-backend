package io.keydra.backup.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.keydra.backup.exception.BackupFailedException;
import io.keydra.keys.dto.ExportedKey;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.Executor;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * The shape a backup is written in, and reading one back.
 *
 * <p>Gzipped NDJSON: a header line, then one key per line. Three properties earn that choice. It is
 * written and read a line at a time, so neither taking a backup nor restoring one has to hold a
 * keyspace in memory — the failure mode of the alternative arrives exactly when the keyspace is
 * large enough for the backup to matter. It compresses, and every destination this can be sent to
 * is billed or throttled by the byte. And it is still a text format: a backup can be looked at,
 * counted with {@code wc -l}, and grepped, which a binary one cannot.
 *
 * <p>The values inside are the store's own serialisation, base64 in the JSON — byte-exact rather
 * than a rendering, which is what makes a restore put back what was taken.
 */
@ApplicationScoped
public class BackupFile {

    /** What a backup is called, so a directory listing sorts by age without opening anything. */
    public static final String SUFFIX = ".ndjson.gz";

    /**
     * What an encrypted one is called.
     *
     * <p>In the name so a listing can say which files need a passphrase without fetching any of
     * them, and so somebody looking at a bucket can tell at a glance.
     */
    public static final String ENCRYPTED_SUFFIX = ".ndjson.gz.enc";

    private final ObjectMapper mapper;

    @Inject
    BackupFile(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Writes a stream of keys to a local file, and answers how many there were.
     *
     * <p>The stream is moved to a worker thread first. Writing a file is the one genuinely blocking
     * thing in this application, and the thread the export emits on is an event loop.
     */
    public Uni<Long> write(Path file, BackupHeader header, Multi<ExportedKey> keys) {
        return write(file, header, keys, null);
    }

    /**
     * Writes a stream of keys to a local file, encrypting it when there is a passphrase.
     *
     * <p>Compressed and then encrypted, in that order: encrypting first would leave nothing worth
     * compressing, because ciphertext does not compress.
     */
    public Uni<Long> write(
            Path file, BackupHeader header, Multi<ExportedKey> keys, String passphrase) {
        return write(file, header, keys, passphrase, null);
    }

    /**
     * Writes a stream of keys to a local file, sealed however the destination says.
     *
     * <p>A key wins over a passphrase when both somehow arrive, and a destination is refused before
     * it is saved if it has both — a place has one way in, and two would be a question about which
     * one a given file used.
     */
    public Uni<Long> write(
            Path file,
            BackupHeader header,
            Multi<ExportedKey> keys,
            String passphrase,
            java.util.List<String> recipients) {
        return Uni.createFrom()
                .deferred(
                        () -> {
                            // Where the caller was, so the answer arrives back there. Without
                            // this everything downstream would continue on the worker thread
                            // the writing moved to, and downstream of a backup is Hibernate
                            // Reactive, which runs only on its own context.
                            Context origin = Vertx.currentContext();
                            Sink sink = open(file, header, passphrase, recipients);
                            return keys.emitOn(Infrastructure.getDefaultWorkerPool())
                                    .onItem()
                                    .invoke(sink::add)
                                    .onItem()
                                    .ignoreAsUni()
                                    .onTermination()
                                    .invoke(sink::close)
                                    .replaceWith(sink::written)
                                    .emitOn(back(origin));
                        });
    }

    /** An executor that puts work back on the Vert.x context it started from. */
    private static Executor back(Context origin) {
        return origin == null
                ? Runnable::run
                : command -> origin.runOnContext(ignored -> command.run());
    }

    /**
     * What the file says it is, or null when it carries no header.
     *
     * <p>Null rather than a failure: a file of plain NDJSON keys is a perfectly good backup, and
     * refusing one because Keydra did not write it would make the format a lock-in.
     */
    public BackupHeader headerOf(Path file) {
        return headerOf(file, null);
    }

    public BackupHeader headerOf(Path file, String passphrase) {
        try (BufferedReader reader = reader(file, passphrase)) {
            String first = reader.readLine();
            if (first == null || !first.contains("\"keydra\"")) {
                return null;
            }
            BackupHeader header = mapper.readValue(first, BackupHeader.class);
            if (header.keydra() != null && header.keydra() > BackupHeader.VERSION) {
                throw new BackupFailedException(
                        "This backup was written by a newer Keydra (format "
                                + header.keydra()
                                + ")");
            }
            return header;
        } catch (BackupFailedException already) {
            throw already;
        } catch (IOException unreadable) {
            throw new BackupFailedException("The backup could not be read", unreadable);
        }
    }

    /**
     * The keys in the file, one at a time.
     *
     * <p>Read on a worker thread, because every line of it is a blocking read from a compressed
     * stream. The reader closes when the stream ends or the subscriber goes away, which matters: a
     * restore that fails halfway must not leave the file open.
     */
    public Multi<ExportedKey> keys(Path file) {
        return keys(file, null);
    }

    public Multi<ExportedKey> keys(Path file, String passphrase) {
        // Where the caller was, so both the keys and any refusal arrive back there. Reading a
        // file is blocking and belongs on a worker thread; everything downstream of a restore
        // is Hibernate Reactive at some point, and it runs nowhere but its own context. The
        // failure path is where this bites: a backup that cannot be opened would otherwise
        // report the real complaint and "not on an event loop" together, as one composite that
        // says neither.
        Context origin = Vertx.currentContext();
        return Multi.createFrom()
                .resource(
                        () -> reader(file, passphrase),
                        reader ->
                                Multi.createFrom()
                                        .items(() -> lines(reader))
                                        .filter(line -> !line.isBlank())
                                        // The header, if there is one. Anything else on the
                                        // first line is a key, so a hand-made file works.
                                        .filter(line -> !line.contains("\"keydra\""))
                                        .map(this::toKey))
                .withFinalizer(BackupFile::close)
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .emitOn(back(origin));
    }

    /**
     * A name that sorts by age and says what it is, without anybody opening it.
     *
     * <p>Down to the millisecond, not the second. Two backups in the same second would be given the
     * same name and the second would silently overwrite the first — which is what happens when
     * somebody presses "back up now" while the schedule is running, and it is the one collision
     * that destroys the copy it looks like it made.
     */
    public static String nameFor(String prefix, java.time.Instant when, boolean encrypted) {
        String stamp =
                java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                        .withZone(java.time.ZoneOffset.UTC)
                        .format(when);
        return prefix + "-" + stamp + (encrypted ? ENCRYPTED_SUFFIX : SUFFIX);
    }

    private Sink open(
            Path file, BackupHeader header, String passphrase, java.util.List<String> recipients) {
        try {
            Files.createDirectories(file.getParent());
            OutputStream out = Files.newOutputStream(file);
            if (recipients != null && !recipients.isEmpty()) {
                out = BackupCipher.encryptTo(out, recipients);
            } else if (passphrase != null && !passphrase.isBlank()) {
                out = BackupCipher.encrypt(out, passphrase);
            }
            BufferedWriter writer =
                    new BufferedWriter(
                            new OutputStreamWriter(
                                    new GZIPOutputStream(out), StandardCharsets.UTF_8));
            Sink sink = new Sink(writer, mapper);
            sink.writeHeader(header);
            return sink;
        } catch (IOException unwritable) {
            throw new BackupFailedException("Could not write " + file, unwritable);
        }
    }

    private BufferedReader reader(Path file, String passphrase) {
        try {
            InputStream in = Files.newInputStream(file);
            if (isEncrypted(file)) {
                // Whether anything was supplied, and whether it is the right kind of thing, is
                // the file's question to answer — it is the only party that knows which mode it
                // was written in, and saying so is more use than "it is encrypted".
                in = BackupCipher.decrypt(in, passphrase);
            }
            return new BufferedReader(
                    new InputStreamReader(new GZIPInputStream(in), StandardCharsets.UTF_8));
        } catch (BackupFailedException already) {
            throw already;
        } catch (IOException unreadable) {
            throw new BackupFailedException("Could not read " + file, unreadable);
        }
    }

    /** Whether a staged file is encrypted, asked of its first bytes rather than of its name. */
    public boolean isEncrypted(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return BackupCipher.looksEncrypted(in.readNBytes(BackupCipher.MAGIC_LENGTH));
        } catch (IOException unreadable) {
            return false;
        }
    }

    private static Stream<String> lines(BufferedReader reader) {
        return reader.lines();
    }

    private static void close(BufferedReader reader) {
        try {
            reader.close();
        } catch (IOException ignored) {
            // The read either finished or already failed; a failure while closing must not
            // replace either answer.
        }
    }

    private ExportedKey toKey(String line) {
        try {
            return mapper.readValue(line, ExportedKey.class);
        } catch (IOException unreadable) {
            throw new BackupFailedException("The backup has a line that is not a key", unreadable);
        }
    }

    /** One open file, written a line at a time on a worker thread. */
    private static final class Sink {

        private final BufferedWriter writer;
        private final ObjectMapper mapper;
        private long written;

        Sink(BufferedWriter writer, ObjectMapper mapper) {
            this.writer = writer;
            this.mapper = mapper;
        }

        void writeHeader(BackupHeader header) throws IOException {
            writer.write(mapper.writeValueAsString(header));
            writer.newLine();
        }

        void add(ExportedKey key) {
            try {
                writer.write(mapper.writeValueAsString(key));
                writer.newLine();
                written++;
            } catch (IOException unwritable) {
                throw new UncheckedIOException(unwritable);
            }
        }

        long written() {
            return written;
        }

        void close() {
            try {
                writer.close();
            } catch (IOException unwritable) {
                throw new BackupFailedException("Could not finish the backup", unwritable);
            }
        }
    }
}
