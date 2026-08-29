package io.keydra.engine.redis;

import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.engine.CopiedKey;
import io.keydra.engine.KeyTransfer;
import io.keydra.engine.RestoreOutcome;
import io.keydra.engine.SerializedKey;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * DUMP and RESTORE, which is how a RESP store hands a value over and takes it back.
 *
 * <p>The payload carries the store's own encoding plus a version and a checksum, so RESTORE refuses
 * a dump from a newer store rather than writing something it cannot read. Keydra does not check the
 * version itself: the store's refusal is more accurate than any table this application could keep.
 */
@ApplicationScoped
public class RespKeyTransfer implements KeyTransfer {

    /**
     * How many restores are in flight when they have to go one at a time.
     *
     * <p>Wide enough to keep the round trips overlapping and far enough under the pool's queue that
     * a migration cannot fill it and make everything else using that target — a browser, a
     * dashboard — start failing. It used to be a fraction of the queue depth, which stopped being a
     * useful way to say it once the queue was sized for pipelines instead of single commands.
     */
    private static final int RESTORE_CONCURRENCY = 64;

    private final RespConnectionPool pool;

    @Inject
    RespKeyTransfer(RespConnectionPool pool) {
        this.pool = pool;
    }

    @Override
    public Uni<List<SerializedKey>> dumpMany(ConnectionProfile profile, List<String> keys) {
        if (keys.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }

        // PTTL beside every DUMP: DUMP alone loses the expiry, and a session restored
        // without its TTL is a session that never expires. Both for every key in one
        // pipeline, so a batch of two hundred keys is one round trip rather than four
        // hundred.
        List<Request> pipeline = new ArrayList<>(keys.size() * 2);
        for (String key : keys) {
            pipeline.add(Request.cmd(Command.PTTL).arg(key));
            pipeline.add(Request.cmd(Command.DUMP).arg(key));
        }

        return pool.batch(profile, pipeline).map(responses -> collect(keys, responses));
    }

    @Override
    public Uni<List<RestoreOutcome>> restoreMany(
            ConnectionProfile profile, List<SerializedKey> keys, boolean replace) {
        if (keys.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }

        // One pipeline when replacing, one command at a time when not.
        //
        // These used to always go one at a time, for a reason that is true as far as it goes: a
        // pipeline carrying one failing command fails as a whole in the client, and a restore has
        // to say which key the store refused. What that reasoning missed is how rarely it happens
        // — the ordinary batch is two hundred keys the target accepts — so every migration paid
        // seven waves of round trips for a case that almost never arrives.
        //
        // The catch is what a failed pipeline leaves behind. Redis runs each command as it
        // arrives; only the client gives up on the batch as a whole. So the commands before the
        // refused one have already taken effect, and walking the same batch again to find which
        // key was refused walks over work that is already done. With REPLACE that is harmless —
        // writing a key twice is writing it once. Without it, the second attempt is told the key
        // is already there, and reports "already there" for a key this migration had itself
        // written a moment earlier.
        //
        // So the fast path is taken only where retrying is the same as not retrying. A migration
        // that is not replacing keeps the old behaviour exactly, which is what its counts depend
        // on.
        if (!replace) {
            return oneAtATime(profile, keys, false);
        }
        return sendChunked(profile, keys.stream().map(key -> restore(key, true)).toList())
                .map(
                        ignored ->
                                keys.stream()
                                        .map(key -> RestoreOutcome.written(key.key()))
                                        .toList())
                .onFailure()
                .recoverWithUni(() -> oneAtATime(profile, keys, true));
    }

    /** The same restores, sent separately, so a refusal can be pinned to the key that caused it. */
    private Uni<List<RestoreOutcome>> oneAtATime(
            ConnectionProfile profile, List<SerializedKey> keys, boolean replace) {
        return Multi.createFrom()
                .iterable(keys)
                .onItem()
                .transformToUni(key -> restoreOne(profile, key, replace))
                .merge(RESTORE_CONCURRENCY)
                .collect()
                .asList();
    }

    /** One RESTORE, built the same way whichever path sends it. */
    private static Request restore(SerializedKey key, boolean replace) {
        Request restore =
                Request.cmd(Command.RESTORE)
                        .arg(key.key())
                        // RESTORE takes the remaining life in milliseconds, and 0 for none.
                        .arg(Math.max(SerializedKey.NO_EXPIRY, key.ttlMillis()))
                        .arg(key.payload());
        if (replace) {
            restore.arg("REPLACE");
        }
        return restore;
    }

    private Uni<RestoreOutcome> restoreOne(
            ConnectionProfile profile, SerializedKey key, boolean replace) {
        return pool.send(profile, restore(key, replace))
                .map(response -> RestoreOutcome.written(key.key()))
                .onFailure()
                .recoverWithItem(failure -> outcome(key.key(), failure));
    }

    /**
     * Reads the pipeline back into keys.
     *
     * <p>Responses come back in the order the commands went out, so the pair for key <i>n</i> is at
     * <i>2n</i> and <i>2n+1</i>. A null DUMP means the key went away between being named and being
     * read, and the key simply drops out.
     */
    private static List<SerializedKey> collect(List<String> keys, List<Response> responses) {
        List<SerializedKey> dumped = new ArrayList<>(keys.size());
        for (int i = 0; i < keys.size(); i++) {
            Response payload = responses.get(i * 2 + 1);
            if (payload != null) {
                dumped.add(
                        new SerializedKey(
                                keys.get(i),
                                normaliseTtl(responses.get(i * 2)),
                                payload.toBytes()));
            }
        }
        return dumped;
    }

    /** PTTL answers -1 for a key with no expiry and -2 for one that is gone; both mean "no TTL". */
    private static long normaliseTtl(Response ttl) {
        long millis = ttl == null ? -1 : ttl.toLong();
        return millis < 0 ? SerializedKey.NO_EXPIRY : millis;
    }

    /**
     * Tells a refusal to overwrite from a real failure.
     *
     * <p>Without REPLACE the store answers BUSYKEY, which is the caller's own instruction being
     * honoured rather than something going wrong. Everything else — a payload from a newer store, a
     * corrupt file — is a failure and keeps the store's own words.
     */
    private static RestoreOutcome outcome(String key, Throwable failure) {
        String message = String.valueOf(failure.getMessage());
        return message.contains("BUSYKEY")
                ? RestoreOutcome.alreadyThere(key)
                : RestoreOutcome.refused(key, message);
    }

    /**
     * What a RESP store says when it will not take the dump this one produced.
     *
     * <p>All of these mean the same thing to a migration — hand the keys over as values instead —
     * and none of them is a reason to stop. Falling back is safe whatever the real cause was: a
     * value-level copy never touches the dump, so it produces the right key even where the dump was
     * the problem.
     *
     * <p>Three different sentences, because stores refuse in their own words:
     *
     * <ul>
     *   <li>Redis and the forks say the payload's version or checksum is wrong. That covers two
     *       faults at once — a dump from a newer store, and a corrupt one — and the store does not
     *       distinguish them.
     *   <li>Garnet implements RESTORE for strings only, so a hash or a list comes back as a
     *       complaint about the command rather than about the payload.
     *   <li>Garnet's RESTORE also takes no REPLACE, and that one lands on every key rather than
     *       some: a migration told to overwrite is refused before the payload is looked at. Read as
     *       a Keydra fault it would be a wrong-arity bug, and it is worth being plain that this
     *       cannot tell the two apart — but the answer is the same either way, and the answer
     *       produces correct keys.
     * </ul>
     */
    private static final List<String> INCOMPATIBLE =
            List.of(
                    "DUMP payload version or checksum are wrong",
                    "RESTORE currently only supports string types",
                    "wrong number of arguments for 'RESTORE'");

    @Override
    public boolean isIncompatibleFormat(String refusal) {
        return refusal != null && INCOMPATIBLE.stream().anyMatch(refusal::contains);
    }

    @Override
    public Uni<List<CopiedKey>> readMany(ConnectionProfile profile, List<String> keys) {
        if (keys.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }

        // What each key is and how long it has left, for the whole batch in one round trip.
        // The read that follows cannot be chosen without the type, so this cannot be folded
        // into it — but it is one round trip per batch, not one per key.
        List<Request> shapes = new ArrayList<>(keys.size() * 2);
        for (String key : keys) {
            shapes.add(Request.cmd(Command.TYPE).arg(key));
            shapes.add(Request.cmd(Command.PTTL).arg(key));
        }

        return pool.batch(profile, shapes)
                .flatMap(
                        described -> {
                            List<Uni<CopiedKey>> reads = new ArrayList<>(keys.size());
                            for (int i = 0; i < keys.size(); i++) {
                                Response type = described.get(i * 2);
                                String shape = type == null ? NONE : type.toString();
                                // TYPE answers "none" for a key that expired between being
                                // named and being read, which is ordinary rather than a fault.
                                if (NONE.equals(shape)) {
                                    continue;
                                }
                                long ttl = normaliseTtl(described.get(i * 2 + 1));
                                reads.add(readOne(profile, keys.get(i), shape, ttl));
                            }
                            if (reads.isEmpty()) {
                                return Uni.createFrom().item(List.of());
                            }
                            return Multi.createFrom()
                                    .iterable(reads)
                                    .onItem()
                                    .transformToUni(read -> read)
                                    .merge(RESTORE_CONCURRENCY)
                                    .filter(copied -> !copied.writes().isEmpty())
                                    .collect()
                                    .asList();
                        });
    }

    /**
     * Reads one key's value as the commands that will rebuild it.
     *
     * <p>Collections are read with their SCAN command rather than with the one that returns the
     * whole thing. Partly because that is how this application enumerates anything — a command that
     * answers with a million elements at once is a command that can stop a server — and partly
     * because RESP3 does not answer the whole-value commands in the shape RESP2 does: HGETALL comes
     * back as a map and ZRANGE ... WITHSCORES as a list of pairs, while the SCAN family answers a
     * flat, binary-safe list under both protocols.
     */
    private Uni<CopiedKey> readOne(
            ConnectionProfile profile, String key, String type, long ttlMillis) {
        return switch (type) {
            case "string" ->
                    pool.send(profile, Request.cmd(Command.GET).arg(key))
                            .map(
                                    value ->
                                            copied(
                                                    key,
                                                    type,
                                                    ttlMillis,
                                                    value == null
                                                            ? List.of()
                                                            : List.of(List.of(value.toBytes()))));
            case "list" ->
                    pool.send(profile, Request.cmd(Command.LRANGE).arg(key).arg(0).arg(-1))
                            .map(value -> copied(key, type, ttlMillis, single(elements(value))));
            case "set" ->
                    scanAll(profile, Command.SSCAN, key)
                            .map(members -> copied(key, type, ttlMillis, single(members)));
            case "hash" ->
                    scanAll(profile, Command.HSCAN, key)
                            .map(fields -> copied(key, type, ttlMillis, single(fields)));
            // ZSCAN answers member then score; ZADD wants score then member.
            case "zset" ->
                    scanAll(profile, Command.ZSCAN, key)
                            .map(scored -> copied(key, type, ttlMillis, single(swapPairs(scored))));
            case "stream" ->
                    pool.send(profile, Request.cmd(Command.XRANGE).arg(key).arg("-").arg("+"))
                            .map(value -> copied(key, type, ttlMillis, streamEntries(value)));
            // A shape this engine has no commands for. Left out rather than half-copied.
            default -> Uni.createFrom().item(copied(key, type, ttlMillis, List.of()));
        };
    }

    /**
     * Walks a collection to its end, one cursor page at a time.
     *
     * <p>The whole value still lands in memory once, which is the same as a dump would do; what
     * this avoids is asking the server to build the whole answer in one reply.
     */
    private Uni<List<byte[]>> scanAll(ConnectionProfile profile, Command command, String key) {
        return scanPage(profile, command, key, "0", new ArrayList<>());
    }

    private Uni<List<byte[]>> scanPage(
            ConnectionProfile profile,
            Command command,
            String key,
            String cursor,
            List<byte[]> collected) {
        Request scan = Request.cmd(command).arg(key).arg(cursor).arg("COUNT").arg(SCAN_PAGE);
        return pool.send(profile, scan)
                .flatMap(
                        page -> {
                            collected.addAll(elements(page.get(1)));
                            String next = page.get(0).toString();
                            return "0".equals(next)
                                    ? Uni.createFrom().item(collected)
                                    : scanPage(profile, command, key, next, collected);
                        });
    }

    /** How many elements a scan asks for per round trip. */
    private static final String SCAN_PAGE = "500";

    /** What TYPE answers for a key that is not there. */
    private static final String NONE = "none";

    private static CopiedKey copied(
            String key, String type, long ttlMillis, List<List<byte[]>> writes) {
        return new CopiedKey(key, type, ttlMillis, writes);
    }

    /** One command's worth of arguments, or nothing at all when the value turned out empty. */
    private static List<List<byte[]>> single(List<byte[]> arguments) {
        return arguments.isEmpty() ? List.of() : List.of(arguments);
    }

    private static List<byte[]> elements(Response value) {
        if (value == null) {
            return List.of();
        }
        List<byte[]> parts = new ArrayList<>(value.size());
        for (Response element : value) {
            parts.add(element.toBytes());
        }
        return parts;
    }

    @Override
    public Uni<List<RestoreOutcome>> writeMany(
            ConnectionProfile profile, List<CopiedKey> keys, boolean replace) {
        if (keys.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }

        // Replacing means the answer does not change anything, so it is not asked for.
        //
        // What EXISTS decides is whether to clear a key before writing it — appending to a list
        // that is already there would run two values together. When every key is going to be
        // replaced, clearing first is right whatever the answer would have been, so the DEL goes
        // in unconditionally and a whole round trip disappears along with a command per key. Over
        // a link where a round trip is 190ms that is half the cost of the batch.
        if (replace) {
            return writeBatch(profile, keys, true, ALL_TAKEN);
        }

        // Not replacing: the question has to be asked. A collection has no "write only if absent"
        // form the way a string does.
        List<Request> exists =
                keys.stream().map(key -> Request.cmd(Command.EXISTS).arg(key.key())).toList();

        return pool.batch(profile, exists)
                .flatMap(
                        answers -> {
                            Set<String> taken = new HashSet<>();
                            for (int i = 0; i < keys.size(); i++) {
                                Response answer = answers.get(i);
                                if (answer != null && answer.toLong() > 0) {
                                    taken.add(keys.get(i).key());
                                }
                            }
                            return writeBatch(profile, keys, replace, taken);
                        });
    }

    /**
     * Every key in the batch as one pipeline.
     *
     * <p>This used to be one pipeline per key, sent concurrently, and that is the difference
     * between a migration and a crawl. Concurrency does not help the way it looks like it should:
     * each key's pipeline occupies a connection until its reply comes back, so however wide the
     * fan-out is set, no more of them are in flight than the pool has connections. Eight
     * connections against a target 190ms away is forty keys a second, which is what a migration to
     * a managed Redis measured — two hundred thousand keys in an hour and a half.
     *
     * <p>Sent together, the same two hundred keys are one round trip. What makes that possible is
     * that these commands are independent of each other: nothing here reads what another wrote, so
     * the only thing the order buys is being able to say which reply belongs to which key — and
     * that is kept by recording how many commands each key contributed.
     *
     * <p>If anything in it is refused the whole pipeline fails in the client, and then the batch is
     * walked one key at a time to find out which. That path always deletes before writing, because
     * a failed pipeline has already done part of its work: appending to a list this same batch just
     * created would copy it twice.
     */
    /**
     * Stands for "every key counts as already there", which is what replacing means.
     *
     * <p>A set that answers yes to everything rather than one built by asking: when the write
     * clears the key first regardless, the answer EXISTS would have given changes nothing, and the
     * round trip that fetches it is pure cost.
     */
    private static final Set<String> ALL_TAKEN =
            new java.util.AbstractSet<>() {
                @Override
                public boolean contains(Object ignored) {
                    return true;
                }

                @Override
                public java.util.Iterator<String> iterator() {
                    return java.util.Collections.emptyIterator();
                }

                @Override
                public int size() {
                    return 0;
                }
            };

    private Uni<List<RestoreOutcome>> writeBatch(
            ConnectionProfile profile, List<CopiedKey> keys, boolean replace, Set<String> taken) {
        List<Request> pipeline = new ArrayList<>();
        List<RestoreOutcome> settled = new ArrayList<>();
        List<CopiedKey> writing = new ArrayList<>();

        for (CopiedKey key : keys) {
            boolean exists = taken.contains(key.key());
            if (exists && !replace) {
                settled.add(RestoreOutcome.alreadyThere(key.key()));
                continue;
            }
            writing.add(key);
            pipeline.addAll(commandsFor(key, exists));
        }

        if (pipeline.isEmpty()) {
            return Uni.createFrom().item(List.copyOf(settled));
        }

        List<CopiedKey> written = List.copyOf(writing);
        return sendChunked(profile, pipeline)
                .map(
                        ignored -> {
                            List<RestoreOutcome> all = new ArrayList<>(settled);
                            written.forEach(key -> all.add(RestoreOutcome.written(key.key())));
                            return List.copyOf(all);
                        })
                .onFailure()
                .recoverWithUni(
                        () ->
                                Multi.createFrom()
                                        .iterable(written)
                                        // Deleting first whatever the earlier answer was: the
                                        // pipeline that failed may already have written this key.
                                        .onItem()
                                        .transformToUni(key -> writeOne(profile, key, true, true))
                                        .merge(RESTORE_CONCURRENCY)
                                        .collect()
                                        .asList()
                                        .map(
                                                outcomes -> {
                                                    List<RestoreOutcome> all =
                                                            new ArrayList<>(settled);
                                                    all.addAll(outcomes);
                                                    return List.copyOf(all);
                                                }));
    }

    /**
     * The most commands to put in one pipeline.
     *
     * <p>Because a single key can be arbitrarily many. A stream is rebuilt one XADD per entry, so
     * one key of ten thousand entries is ten thousand commands — which is what a batch of two
     * hundred keys turned into, and what the pool refused with "Redis waiting queue is full". Two
     * keys out of nine hundred thousand failed every run, deterministically, and neither a deeper
     * queue nor a more patient retry touched it: the pipeline was simply larger than anything the
     * pool could ever hold at once.
     *
     * <p>So the ceiling is on the pipeline rather than on the queue, and it holds however a key is
     * shaped. A batch of ordinary keys still goes in one round trip; a stream of a million entries
     * goes in as many as it needs and no more.
     */
    private static final int MAX_PIPELINE = 1_000;

    /**
     * Sends commands in pipelines no larger than the pool can hold, in the order given.
     *
     * <p>One after another rather than at once: the commands rebuilding one key have to arrive in
     * order — a stream's entries are appended, and appending them out of order is a different
     * stream — and chunks sent concurrently arrive in whatever order they finish.
     */
    private Uni<Void> sendChunked(ConnectionProfile profile, List<Request> commands) {
        if (commands.size() <= MAX_PIPELINE) {
            return pool.batch(profile, commands).replaceWithVoid();
        }
        Uni<Void> chain = Uni.createFrom().voidItem();
        for (int from = 0; from < commands.size(); from += MAX_PIPELINE) {
            List<Request> chunk =
                    commands.subList(from, Math.min(from + MAX_PIPELINE, commands.size()));
            chain = chain.flatMap(ignored -> pool.batch(profile, chunk).replaceWithVoid());
        }
        return chain;
    }

    /** The commands that rebuild one key: clear it if it is there, write it, then set its life. */
    private static List<Request> commandsFor(CopiedKey key, boolean exists) {
        List<Request> commands = new ArrayList<>(key.writes().size() + 2);
        if (exists) {
            // Removed rather than written over: pushing onto a list that is already there
            // appends to it, and the copy would be the two values run together.
            commands.add(Request.cmd(Command.DEL).arg(key.key()));
        }
        for (List<byte[]> arguments : key.writes()) {
            Request write = Request.cmd(commandFor(key.type())).arg(key.key());
            arguments.forEach(write::arg);
            commands.add(write);
        }
        if (key.ttlMillis() > SerializedKey.NO_EXPIRY) {
            commands.add(
                    Request.cmd(Command.PEXPIRE)
                            .arg(key.key())
                            .arg(String.valueOf(key.ttlMillis())));
        }
        return commands;
    }

    private Uni<RestoreOutcome> writeOne(
            ConnectionProfile profile, CopiedKey key, boolean replace, boolean exists) {
        if (exists && !replace) {
            return Uni.createFrom().item(RestoreOutcome.alreadyThere(key.key()));
        }

        return sendChunked(profile, commandsFor(key, exists))
                .map(ignored -> RestoreOutcome.written(key.key()))
                .onFailure()
                .recoverWithItem(failure -> outcome(key.key(), failure));
    }

    /** The command that writes one entry of this shape back. */
    private static Command commandFor(String type) {
        return switch (type) {
            case "string" -> Command.SET;
            case "list" -> Command.RPUSH;
            case "set" -> Command.SADD;
            case "zset" -> Command.ZADD;
            case "hash" -> Command.HSET;
            case "stream" -> Command.XADD;
            default -> throw new IllegalStateException("no write for " + type);
        };
    }

    private static List<byte[]> swapPairs(List<byte[]> pairs) {
        List<byte[]> swapped = new ArrayList<>(pairs.size());
        for (int i = 0; i + 1 < pairs.size(); i += 2) {
            swapped.add(pairs.get(i + 1));
            swapped.add(pairs.get(i));
        }
        return swapped;
    }

    /**
     * A stream's entries, each as the arguments of one XADD.
     *
     * <p>The original ids are kept rather than letting the target mint new ones. An id is part of
     * what a stream entry is — consumers remember where they were by it — so a copy that renumbered
     * them would not be a copy.
     */
    private static List<List<byte[]>> streamEntries(Response value) {
        List<List<byte[]>> entries = new ArrayList<>(value.size());
        for (Response entry : value) {
            List<byte[]> arguments = new ArrayList<>();
            arguments.add(entry.get(0).toBytes());
            for (Response field : entry.get(1)) {
                arguments.add(field.toBytes());
            }
            entries.add(arguments);
        }
        return entries;
    }
}
