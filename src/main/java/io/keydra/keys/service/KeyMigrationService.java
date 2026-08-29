package io.keydra.keys.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.keydra.approvals.entity.ApprovalKind;
import io.keydra.approvals.service.Approvals;
import io.keydra.authz.entity.Permission;
import io.keydra.authz.service.CallerPermissions;
import io.keydra.cluster.service.Leadership;
import io.keydra.common.graphql.Cursors;
import io.keydra.common.vertx.OwnContext;
import io.keydra.common.workload.Workload;
import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.persistence.ConnectionProfileRepository;
import io.keydra.connections.service.ConnectionService;
import io.keydra.connections.service.GuardedTargets;
import io.keydra.engine.CopiedKey;
import io.keydra.engine.EngineSelector;
import io.keydra.engine.KeyTransfer;
import io.keydra.engine.RestoreOutcome;
import io.keydra.engine.SerializedKey;
import io.keydra.events.dto.NotificationCategory;
import io.keydra.events.service.NotificationHub;
import io.keydra.keys.approval.KeyApprovalPayloads.MigrateKeysPayload;
import io.keydra.keys.dto.ExportKeysRequest;
import io.keydra.keys.dto.MigrateKeysRequest;
import io.keydra.keys.dto.MigrationJob;
import io.keydra.keys.dto.MigrationQuery;
import io.keydra.keys.dto.MigrationSlice;
import io.keydra.keys.entity.MigrationRun;
import io.keydra.keys.exception.MigrationRefusedException;
import io.keydra.keys.persistence.MigrationRepository;
import io.keydra.keys.script.KeyDecision;
import io.keydra.keys.script.KeyScript;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.runtime.ShutdownEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.smallrye.mutiny.subscription.Cancellable;
import io.smallrye.mutiny.subscription.FixedDemandPacer;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Moves keys from one target to another.
 *
 * <p>Keydra reads from the source and writes to the destination rather than asking the source to
 * hand the keys over itself. RESP has a MIGRATE command that would do that in one step and never
 * put the data on this machine, but it needs the source server to be able to reach the destination
 * — and in the deployments this application is for, Keydra is frequently the only thing that can
 * reach both, through two different tunnels. One path that always works beats a faster one that
 * silently does not.
 *
 * <p>Nothing is held: the keyspace is walked with a cursor, a batch at a time, and each batch is
 * dumped, restored and dropped before the next is fetched. Peak memory is one batch, whatever the
 * size of the keyspace — the walk itself is what makes this usable on a large one.
 *
 * <p>Jobs run detached from the request that started them and report over the notification hub.
 * Moving a large keyspace takes minutes, and a request held open for that long is one reload away
 * from losing sight of a job that is still running.
 *
 * <p>Each one is also a row. In memory alone, a restart did not merely lose the history — it made a
 * migration that was interrupted halfway indistinguishable from one that never happened, and those
 * two call for opposite decisions from whoever is deciding whether to run it again. The row is
 * written when the job starts, checkpointed as it walks, and finished when it ends; anything still
 * marked running at the next startup was interrupted, because nothing else could have left it
 * there.
 */
@ApplicationScoped
public class KeyMigrationService implements Workload {

    private static final Logger LOG = Logger.getLogger(KeyMigrationService.class);

    /** A running job: what it is, what it has done so far, and the handle that stops it. */
    private static final class Running {
        private final String id;
        private final long sourceId;
        private final long targetId;
        private final String match;

        /**
         * How many keys this expects to move, once something has been able to say.
         *
         * <p>Volatile and set at most once, from off the walk: it is answered by the source a
         * moment after the job starts, and every snapshot taken after that carries it.
         */
        private volatile Long total;

        private final AtomicLong scanned = new AtomicLong();
        private final AtomicLong migrated = new AtomicLong();
        private final AtomicLong skipped = new AtomicLong();
        private final AtomicLong failed = new AtomicLong();

        /** Keys a script turned down, which were never offered to the target. */
        private final AtomicLong dropped = new AtomicLong();

        /**
         * The job's own compiled script, or null.
         *
         * <p>Per job rather than per request or per key: compiling once is the cheap part, and an
         * environment shared between two jobs would let one job's script see what another left in
         * its globals.
         */
        private KeyScript script;

        private final AtomicLong deleted = new AtomicLong();
        private final AtomicLong batches = new AtomicLong();
        private volatile String reason;

        /**
         * Set once the target has refused a serialised key outright.
         *
         * <p>Two RESP forks do not always agree on the serialised form — Redis 8 stamps its dumps
         * with an RDB version Valkey 9 will not read — and when they do not, they never will for
         * this pair. So the job learns it once and copies by value from then on, rather than
         * offering every batch a dump the target has already said it cannot take.
         */
        private volatile boolean byValue;

        private volatile MigrationJob.State state = MigrationJob.State.RUNNING;
        private volatile Cancellable cancellable;

        /**
         * When the job started, which for one that has changed hands is when it first started.
         *
         * <p>Passed in rather than taken here, so a migration taken over by another instance is
         * still the migration somebody launched at three rather than a new one that appeared at
         * half past. The counters below are this attempt's; the time is the job's.
         */
        private final Instant startedAt;

        private final String startedBy;
        private volatile Instant finishedAt;

        /**
         * How many times this job has already been picked up after an instance went away.
         *
         * <p>Carried by the running job as well as by the row so that every snapshot it publishes
         * says it. A progress bar that goes back to the beginning needs the sentence that explains
         * it in the same message, not on the next page load.
         */
        private final int resumed;

        Running(String id, long sourceId, long targetId, String match, String startedBy) {
            this(id, sourceId, targetId, match, startedBy, Instant.now(), 0);
        }

        Running(
                String id,
                long sourceId,
                long targetId,
                String match,
                String startedBy,
                Instant startedAt,
                int resumed) {
            this.id = id;
            this.sourceId = sourceId;
            this.targetId = targetId;
            this.match = match;
            this.startedBy = startedBy;
            this.startedAt = startedAt;
            this.resumed = resumed;
        }

        MigrationJob snapshot() {
            return new MigrationJob(
                    id,
                    sourceId,
                    targetId,
                    match,
                    total,
                    scanned.get(),
                    migrated.get(),
                    skipped.get(),
                    failed.get(),
                    dropped.get(),
                    deleted.get(),
                    reason,
                    state,
                    startedAt,
                    finishedAt,
                    startedBy,
                    resumed);
        }
    }

    /**
     * How many batches between writes of a job's counters to its row.
     *
     * <p>Far coarser than the progress broadcasts, and for the opposite reason: a socket message is
     * cheap and a database write is not. What the row has to answer after an interruption is "how
     * far had it got", to which the last few hundred keys make no difference.
     */
    private static final long CHECKPOINT_EVERY = 50;

    private final ConnectionService connections;
    private final EngineSelector engines;
    private final KeyTransferService transfers;
    private final KeyService keys;
    private final MigrationRepository repository;
    private final ConnectionProfileRepository profiles;
    private final CallerPermissions caller;
    private final NotificationHub hub;
    private final Approvals approvals;
    private final Leadership leadership;
    private final Vertx vertx;

    /** For the one field of a migration row that is not a column: the request that started it. */
    private final ObjectMapper json;

    private final int progressEvery;
    private final Map<String, Running> running = new ConcurrentHashMap<>();

    /**
     * How many batches are in flight at once.
     *
     * <p>Peak memory is this times a batch of values, so it is the number that decides whether a
     * migration is bounded — which is why it is a setting rather than a literal. Four keeps a local
     * link busy without holding much; a deployment moving between two distant servers, where the
     * round trip is the whole cost, is the case for raising it.
     */
    private final int batchConcurrency;

    /** Whether this deployment runs migration scripts at all. See application.properties. */
    private final boolean scriptingEnabled;

    @Inject
    KeyMigrationService(
            ConnectionService connections,
            EngineSelector engines,
            KeyTransferService transfers,
            KeyService keys,
            MigrationRepository repository,
            ConnectionProfileRepository profiles,
            CallerPermissions caller,
            NotificationHub hub,
            Approvals approvals,
            Leadership leadership,
            Vertx vertx,
            ObjectMapper json,
            @ConfigProperty(name = "keydra.keys.migration-progress-every", defaultValue = "1")
                    int progressEvery,
            @ConfigProperty(name = "keydra.keys.migration-batch-concurrency", defaultValue = "4")
                    int batchConcurrency,
            @ConfigProperty(name = "keydra.keys.scripting.enabled", defaultValue = "false")
                    boolean scriptingEnabled) {
        this.batchConcurrency = Math.max(1, batchConcurrency);
        this.scriptingEnabled = scriptingEnabled;
        this.connections = connections;
        this.engines = engines;
        this.transfers = transfers;
        this.keys = keys;
        this.repository = repository;
        this.profiles = profiles;
        this.caller = caller;
        this.hub = hub;
        this.approvals = approvals;
        this.leadership = leadership;
        this.vertx = vertx;
        this.json = json;
        this.progressEvery = Math.max(1, progressEvery);
    }

    /**
     * Stops the walks, and deliberately records nothing.
     *
     * <p>A job stopping because the application is stopping is not a job somebody cancelled, and
     * writing "cancelled" here would put a person's decision in the history where there was none.
     * The rows stay as they are and the next startup calls them what they were: interrupted.
     */
    void onStop(@Observes ShutdownEvent event) {
        running.values()
                .forEach(
                        job -> {
                            if (job.cancellable != null) {
                                job.cancellable.cancel();
                            }
                        });
        running.clear();
    }

    /** How many migrations this instance is walking, for the gauge that reports it. */
    public int runningCount() {
        return running.size();
    }

    /**
     * Every job this instance knows about, whichever target started it.
     *
     * <p>A migration is between two targets, so a list kept under one of them is a list somebody
     * has to already know where to look for. This is the one that answers "what is moving".
     *
     * <p>Read from the rows and then overlaid with what is running: the row is checkpointed rather
     * than written per batch, so for a job still going the live counters are the newer of the two.
     *
     * <p>Filtered to what the caller can see, and by both ends. A migration names two targets, and
     * somebody who can reach neither has no business knowing that keys moved between them.
     */
    public Uni<List<MigrationJob>> allJobs() {
        return repository
                .recent()
                .map(runs -> runs.stream().map(MigrationRun::toJob).map(this::live).toList())
                .flatMap(this::onlyVisible);
    }

    /**
     * One page of migrations, cut on the server.
     *
     * <p>Which targets the caller can see is settled first, because it is what the page has to be
     * narrowed by: a page taken before the filter and then filtered would come back short, and a
     * table asking for twenty rows would show however many of those twenty survived. Two questions
     * rather than one, and the first is over the catalogue of servers, which is tens of rows.
     *
     * <p>The live counters are laid over afterwards, as they are everywhere else here: the row is
     * checkpointed rather than written per batch, so for a job still going the running numbers are
     * the newer of the two.
     */
    @WithSession
    public Uni<MigrationSlice> pageOfJobs(MigrationQuery query, Cursors.Position after, int size) {
        return profiles.allIds()
                .flatMap(caller::visible)
                .flatMap(visible -> repository.page(visible, query, after, size))
                .map(
                        rows ->
                                new MigrationSlice(
                                        rows.rows().stream()
                                                .map(MigrationRun::toJob)
                                                .map(this::live)
                                                .toList(),
                                        rows.total(),
                                        rows.running(),
                                        rows.hasMore()));
    }

    /** The migrations started from one target, newest first. Filtered like the whole list. */
    public Uni<List<MigrationJob>> jobsFor(Long sourceConnectionId) {
        return allJobs()
                .map(
                        found ->
                                found.stream()
                                        .filter(
                                                job ->
                                                        job.sourceConnectionId()
                                                                == sourceConnectionId)
                                        .toList());
    }

    /** The live snapshot of a job that is still going, or the row as it stands. */
    private MigrationJob live(MigrationJob stored) {
        Running job = running.get(stored.id());
        return job == null ? stored : job.snapshot();
    }

    private Uni<List<MigrationJob>> onlyVisible(List<MigrationJob> found) {
        List<Long> involved =
                found.stream()
                        .flatMap(
                                job ->
                                        java.util.stream.Stream.of(
                                                job.sourceConnectionId(), job.targetConnectionId()))
                        .distinct()
                        .toList();
        return caller.visible(involved)
                .map(
                        visible ->
                                found.stream()
                                        .filter(
                                                job ->
                                                        visible.contains(job.sourceConnectionId())
                                                                && visible.contains(
                                                                        job.targetConnectionId()))
                                        .toList());
    }

    /**
     * Starts a migration and answers the job it created, before any keys have moved.
     *
     * <p>Who asked is a parameter rather than something read from the security context here. A
     * migration is started by a person through a request and by the clock through a schedule, and
     * the second one has no request to read an identity from — asking anyway blocks the thread the
     * scheduler runs on, which is how this was found. The schedule passes the name of whoever
     * arranged it, which is also the more useful answer.
     *
     * <p>The two profiles are loaded first so an unknown target, a target that is the source, or a
     * store that cannot serialise a value is refused now rather than by a job that starts and
     * immediately fails.
     */
    public Uni<MigrationJob> start(Long sourceId, MigrateKeysRequest request, String startedBy) {
        if (sourceId.equals(request.targetConnectionId())) {
            return Uni.createFrom()
                    .failure(new MigrationRefusedException("A target cannot migrate to itself"));
        }
        /*
         * Refused here, before anything is loaded or written down. A deployment that has no use for
         * scripts says so once in configuration rather than trusting a permission never to be
         * granted by mistake — so this is checked whoever is asking and whatever they hold.
         */
        if (request.hasScript() && !scriptingEnabled) {
            return Uni.createFrom()
                    .failure(
                            new MigrationRefusedException(
                                    "This instance does not run migration scripts"));
        }

        return allowedToScript(request)
                .flatMap(ignored -> namedWhereGuarded(sourceId, request))
                .flatMap(ignored -> agreedWhereAsked(sourceId, request))
                .flatMap(ignored -> begin(sourceId, request, startedBy));
    }

    /**
     * The same migration, on a request a second person has already agreed to.
     *
     * <p>Called by nothing but the approvals runner. What it skips is the two questions that are
     * about whoever is making the request — naming the target, and holding {@code script:run} —
     * because on this path there is nobody making one: the work runs on a context of its own, hours
     * later. Neither is unasked. The naming happened when the request was raised, and {@code
     * script:run} is asked of the approver before they are offered the request and of the requester
     * again at the moment it runs.
     *
     * <p>What this deployment allows at all is asked here as everywhere else, because that is a
     * fact about the instance rather than about a person.
     */
    public Uni<MigrationJob> startApproved(
            Long sourceId, MigrateKeysRequest request, String startedBy) {
        if (request.hasScript() && !scriptingEnabled) {
            return Uni.createFrom()
                    .failure(
                            new MigrationRefusedException(
                                    "This instance does not run migration scripts"));
        }
        return begin(sourceId, request, startedBy);
    }

    /**
     * Either end can ask for two people, and one request covers both.
     *
     * <p>Both ends are stored, so whoever answers it holds {@code migration:run} on each of them —
     * a second person with standing at the destination and none at the source would be agreeing to
     * half of an operation.
     *
     * <p>The source only counts when the keys are being taken off it, which is the same line phase
     * 59 draws for the naming: a copy reads and leaves everything where it was.
     */
    private Uni<Void> agreedWhereAsked(Long sourceId, MigrateKeysRequest request) {
        return connections
                .load(sourceId)
                .flatMap(
                        source ->
                                connections
                                        .load(request.targetConnectionId())
                                        .flatMap(
                                                destination ->
                                                        approvals.require(
                                                                source,
                                                                destination,
                                                                ApprovalKind.MIGRATE_KEYS,
                                                                new MigrateKeysPayload(request),
                                                                request.deleteFromSource())));
    }

    /**
     * Both ends of a move, where either of them asks to be named.
     *
     * <p>The destination always, because a migration writes into it. The source only when {@code
     * deleteFromSource} is set — a copy reads and leaves everything where it was, and asking
     * somebody to name a server they are not changing is how a guard stops being read.
     *
     * <p>Two names rather than one, because when both ends are guarded one name cannot say which
     * was meant. It is also the shape of the thing: a move is two operations on two servers.
     */
    private Uni<Void> namedWhereGuarded(Long sourceId, MigrateKeysRequest request) {
        return connections
                .load(request.targetConnectionId())
                .invoke(
                        destination ->
                                GuardedTargets.requireNamed(
                                        destination,
                                        request.confirmTarget(),
                                        "This would write keys into it"
                                                + (request.replace()
                                                        ? ", replacing what is there"
                                                        : "")))
                .flatMap(
                        ignored ->
                                request.deleteFromSource()
                                        ? connections
                                                .load(sourceId)
                                                .invoke(
                                                        source ->
                                                                GuardedTargets.requireNamed(
                                                                        source,
                                                                        request.confirmSource(),
                                                                        "This would take keys off"
                                                                            + " it and not put them"
                                                                            + " back"))
                                                .replaceWithVoid()
                                        : Uni.createFrom().voidItem());
    }

    /**
     * The second permission a script needs, checked here rather than on the endpoint.
     *
     * <p>An annotation cannot express it: whether a migration needs {@code script:run} depends on
     * whether the request carries a script, which is in the payload. And it is asked for as well as
     * the permission to run the migration, never instead of it — {@code migration:run} is about
     * this target and this is about Keydra, which is where the script would actually run.
     */
    private Uni<Void> allowedToScript(MigrateKeysRequest request) {
        if (!request.hasScript()) {
            return Uni.createFrom().voidItem();
        }
        return caller.holds(Permission.SCRIPT_RUN, null)
                .flatMap(
                        held ->
                                Boolean.TRUE.equals(held)
                                        ? Uni.createFrom().voidItem()
                                        : Uni.createFrom()
                                                .failure(
                                                        new MigrationRefusedException(
                                                                "Running a script needs"
                                                                        + " script:run on this"
                                                                        + " instance")));
    }

    private Uni<MigrationJob> begin(Long sourceId, MigrateKeysRequest request, String startedBy) {

        // One after the other, not both at once. Each load runs in a Hibernate Reactive
        // session, and a session serves one operation at a time: combining the two lets the
        // second read from a session the first has already closed, which surfaces as
        // "Session/EntityManager is closed" and only some of the time, because whether they
        // overlap depends on how fast the first query answers.
        return connections
                .load(sourceId)
                .flatMap(
                        source ->
                                connections
                                        .load(request.targetConnectionId())
                                        .flatMap(
                                                target -> {
                                                    // Both capabilities are checked before
                                                    // the job exists, so a refusal answers
                                                    // this request rather than arriving as a
                                                    // broadcast about a job that never
                                                    // moved anything.
                                                    transfers.transfer(source);
                                                    transfers.transfer(target);
                                                    return record(
                                                                    source, target, request,
                                                                    startedBy)
                                                            .map(
                                                                    id ->
                                                                            launch(
                                                                                    id, source,
                                                                                    target, request,
                                                                                    startedBy));
                                                }));
    }

    /**
     * Writes the row before the job exists, and answers the id both will use.
     *
     * <p>Before, not after: a job whose row is written when it finishes is a job that leaves no
     * trace while it is running, which is exactly the window this whole change is about.
     */
    private Uni<String> record(
            ConnectionProfile source,
            ConnectionProfile target,
            MigrateKeysRequest request,
            String startedBy) {
        MigrationRun run = new MigrationRun();
        run.id = UUID.randomUUID().toString();
        run.sourceConnectionId = source.id;
        run.targetConnectionId = target.id;
        run.matchPattern = request.match();
        run.startedBy = startedBy;
        run.instanceId = leadership.instanceId();
        // What it was asked to do, not only what it is doing. Without this the row describes a
        // migration and does not amount to one, so an instance finding it abandoned can see that
        // work stopped and has no way to carry it on.
        run.request = asJson(request);
        return repository.start(run).map(saved -> saved.id);
    }

    /**
     * Starts a migration again on this instance, from the row somebody else left behind.
     *
     * <p>Called by the sweep, which has already claimed the row — so by the time this runs, this
     * instance owns the job and the one that was walking it has either gone or will stop at its
     * next checkpoint.
     *
     * <p>The walk begins again rather than in the middle, and that is the honest description. There
     * is no cursor to resume from: a SCAN cursor is a position in a keyspace that is still being
     * written to, and one saved five minutes ago on a server that has since resharded is a promise
     * this cannot keep. What restarting costs is a second pass over names; what it does not cost is
     * correctness — a migration that deletes its source no longer sees what it already moved, and
     * one that copies either overwrites with the same value or skips what is already there. It
     * arrives at the same end state, and it gets there without anybody being asked.
     */
    Uni<Boolean> resume(MigrationRun run) {
        MigrateKeysRequest request = fromJson(run.request);
        if (request == null) {
            /*
             * An old row, from before a migration wrote down what it had been asked to do — or one
             * this version cannot read. Interrupted rather than failed, because that is what
             * happened: nothing refused anything, the process went away and the record was not
             * complete enough to carry on from.
             */
            return abandon(
                    run,
                    MigrationJob.State.INTERRUPTED,
                    "Keydra restarted and this migration did not record what it was asked to do");
        }
        if (request.hasScript() && !scriptingEnabled) {
            return abandon(
                    run, MigrationJob.State.FAILED, "This instance does not run migration scripts");
        }
        return connections
                .load(run.sourceConnectionId)
                .flatMap(
                        source ->
                                connections
                                        .load(run.targetConnectionId)
                                        .map(
                                                target -> {
                                                    transfers.transfer(source);
                                                    transfers.transfer(target);
                                                    launch(
                                                            run.id,
                                                            source,
                                                            target,
                                                            request,
                                                            run.startedBy,
                                                            run.startedAt,
                                                            run.resumed);
                                                    return true;
                                                }))
                .onFailure()
                .recoverWithUni(
                        failure ->
                                abandon(
                                        run,
                                        MigrationJob.State.FAILED,
                                        "Could not be resumed: "
                                                + (failure.getMessage() == null
                                                        ? failure.getClass().getSimpleName()
                                                        : failure.getMessage())));
    }

    /**
     * Ends a job that cannot be carried on, saying why.
     *
     * <p>Because the alternative is a row that says RUNNING for ever. A target that has been
     * deleted, a stored request this version cannot read, a script an operator has since switched
     * off — none of those can be walked, and all of them are better as a failure with a sentence
     * than as a job that is apparently still going.
     */
    private Uni<Boolean> abandon(MigrationRun run, MigrationJob.State state, String why) {
        MigrationJob ending =
                new MigrationJob(
                        run.id,
                        run.sourceConnectionId,
                        run.targetConnectionId,
                        run.matchPattern,
                        run.totalKeys,
                        run.scanned,
                        run.migrated,
                        run.skipped,
                        run.failed,
                        run.dropped,
                        run.deleted,
                        why,
                        state,
                        run.startedAt,
                        Instant.now(),
                        run.startedBy,
                        run.resumed);
        LOG.warnf("Migration %s cannot be resumed: %s", run.id, why);
        publish(ending);
        /*
         * Waited on, unlike every other ending here. The usual rule is that a broadcast should not
         * wait on a database write — the person watching matters more than the record. This is the
         * one place the record is the point: the sweep exists to stop a row saying RUNNING for ever,
         * and a sweep that answered before it had written that down would be a sweep that had not
         * done its job yet.
         */
        return repository.record(ending, leadership.instanceId()).replaceWith(false);
    }

    /** The request as it is stored, or null when it cannot be written down. */
    private String asJson(MigrateKeysRequest request) {
        try {
            return json.writeValueAsString(request);
        } catch (JsonProcessingException impossible) {
            // A record of plain fields, so this does not happen; a migration that refused to start
            // because its own description would not serialise would be the wrong trade if it did.
            LOG.warn("Could not write down what a migration was asked to do", impossible);
            return null;
        }
    }

    /** The request as it was stored, or null when this version cannot read it. */
    private MigrateKeysRequest fromJson(String stored) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            return json.readValue(stored, MigrateKeysRequest.class);
        } catch (JsonProcessingException unreadable) {
            LOG.warnf(unreadable, "Could not read a stored migration request");
            return null;
        }
    }

    /**
     * Whether this instance is actually walking that job.
     *
     * <p>Asked by the handover, which compares it with what the rows say. A row under this
     * instance's name that this instance is not walking is a job that stopped without recording an
     * ending — after a crash, that is every row it had.
     */
    boolean isRunning(String jobId) {
        return running.containsKey(jobId);
    }

    /** Stops a job. The keys already written stay written: a migration is not a transaction. */
    public boolean cancel(String jobId) {
        Running job = running.remove(jobId);
        if (job == null) {
            return false;
        }
        job.state = MigrationJob.State.CANCELLED;
        job.finishedAt = Instant.now();
        job.cancellable.cancel();
        // Removed from the running map already, so the ending is published from the last
        // snapshot the job took of itself.
        ended(job.snapshot());
        return true;
    }

    /**
     * Works out how many keys this migration is going to move, if anything can.
     *
     * <p>Three answers, and only two of them exist. A caller who named the keys has already said
     * how many there are. A migration of the whole database is counted by the store, which keeps
     * that number anyway. A glob has no answer at all — a store cannot say how many keys match a
     * pattern without walking the keyspace, and walking it is the job — so it is left null and the
     * dialog says what it can count instead of drawing a bar against a denominator it invented.
     *
     * <p>Asked alongside the job rather than before it. The count comes from the source a moment
     * after the walk starts, and a job that waited for it would be a job that starts late for a
     * number that only decorates it. Failing to get one is not a failure: the bar loses its
     * denominator and the migration carries on.
     */
    private void learnTotal(ConnectionProfile source, MigrateKeysRequest request, Running job) {
        if (request.keys() != null && !request.keys().isEmpty()) {
            job.total = capped((long) request.keys().size(), request.limit());
            return;
        }
        if (!movesEverything(request.match())) {
            return;
        }
        engines.forProfile(source)
                .metrics()
                .ifPresent(
                        metrics ->
                                metrics.sample(source)
                                        .subscribe()
                                        .with(
                                                reading -> {
                                                    if (reading.keyCount() != null) {
                                                        job.total =
                                                                capped(
                                                                        reading.keyCount(),
                                                                        request.limit());
                                                    }
                                                },
                                                failure ->
                                                        LOG.debugf(
                                                                failure,
                                                                "Could not count the keys on"
                                                                    + " profile %d; the migration"
                                                                    + " runs without a total",
                                                                source.id)));
    }

    /** Whether a pattern selects the whole keyspace, which is the case a count can answer. */
    private static boolean movesEverything(String match) {
        return match == null || match.isBlank() || "*".equals(match.trim());
    }

    /** A limit is a ceiling on the job, so it is a ceiling on the total as well. */
    private static Long capped(Long total, Integer limit) {
        if (limit == null || limit <= 0) {
            return total;
        }
        return Math.min(total, limit.longValue());
    }

    private MigrationJob launch(
            String id,
            ConnectionProfile source,
            ConnectionProfile target,
            MigrateKeysRequest request,
            String startedBy) {
        return launch(id, source, target, request, startedBy, Instant.now(), 0);
    }

    private MigrationJob launch(
            String id,
            ConnectionProfile source,
            ConnectionProfile target,
            MigrateKeysRequest request,
            String startedBy,
            Instant startedAt,
            int resumed) {
        Running job =
                new Running(
                        id, source.id, target.id, request.match(), startedBy, startedAt, resumed);
        // Compiled before the walk starts, so a script that does not parse is a refusal of this
        // request rather than a job that starts, moves nothing and reports a failure.
        job.script = request.hasScript() ? KeyScript.compile(request.script()) : null;
        running.put(id, job);
        learnTotal(source, request, job);

        job.cancellable =
                transfers
                        .inBatches(
                                transfers
                                        .names(
                                                source,
                                                new ExportKeysRequest(
                                                        request.keys(),
                                                        request.match(),
                                                        // The migration's own limit, already
                                                        // resolved. Handing the raw one over
                                                        // meant a migration with no limit
                                                        // inherited the export's default of a
                                                        // hundred thousand instead of its own,
                                                        // so "move everything" moved the first
                                                        // hundred thousand keys and reported
                                                        // itself done — with nothing to say it
                                                        // had stopped short.
                                                        request.limitOrDefault()),
                                                request.type())
                                        .invoke(ignored -> job.scanned.incrementAndGet())
                                        .plug(names -> paced(names, request.maxKeysPerSecond())))
                        // Several batches at once, bounded. One at a time was the first
                        // version and its reasoning was half right: the point of walking a
                        // keyspace is that peak memory does not grow with it, and unbounded
                        // concurrency would give that up. A fixed number does not — memory is
                        // that number times a batch, which is a constant — and it is the
                        // difference between a migration that saturates the link and one that
                        // spends most of its time waiting for a round trip it could have
                        // overlapped. Keys are independent of each other, so nothing here needs
                        // them in order.
                        .onItem()
                        .transformToUni(batch -> moveBatch(source, target, batch, request, id, job))
                        .merge(batchConcurrency)
                        .subscribe()
                        .with(
                                ignored -> {},
                                failure -> finish(id, MigrationJob.State.FAILED, failure),
                                () -> finish(id, MigrationJob.State.DONE, null));

        MigrationJob started = job.snapshot();
        publish(started);
        return started;
    }

    /**
     * Holds the walk to a stated number of keys a second, or lets it run.
     *
     * <p>Applied to the names rather than to the batches, which is what makes it a ceiling: batches
     * are moved several at a time, so pacing them would give a rate multiplied by however many are
     * in flight — a number nobody typed.
     *
     * <p>Worth having because a migration is usually run against a server somebody else is using.
     * The tool that saturates the link is also the tool that makes the application on the other end
     * slow, and "go as fast as possible" is the right default and the wrong only option.
     *
     * <p>Paced in tenths of a second: a whole second of demand released at once is a second of
     * nothing followed by a burst, which is the shape a rate limit is meant to remove.
     */
    private static Multi<String> paced(Multi<String> names, Integer keysPerSecond) {
        if (keysPerSecond == null || keysPerSecond <= 0) {
            return names;
        }
        long perTick = Math.max(1, keysPerSecond / 10);
        return names.paceDemand()
                .on(Infrastructure.getDefaultWorkerPool())
                .using(new FixedDemandPacer(perTick, Duration.ofMillis(100)));
    }

    private Uni<Void> moveBatch(
            ConnectionProfile source,
            ConnectionProfile target,
            List<String> batch,
            MigrateKeysRequest request,
            String id,
            Running job) {
        return move(source, target, batch, request, job)
                .flatMap(outcomes -> afterRestore(source, batch, outcomes, request, job))
                .invoke(ignored -> publishEvery(job))
                .replaceWithVoid();
    }

    /**
     * Moves one batch, the fast way if the two stores will have it and the slow way if not.
     *
     * <p>The fast way hands the target the source's own bytes; the slow way reads the values and
     * writes them back with ordinary commands. Which one works is a property of the pair of stores
     * rather than of the key, so the first refusal decides it for the whole job — and the batch
     * that was refused is moved again the slow way rather than counted as lost.
     */
    private Uni<List<RestoreOutcome>> move(
            ConnectionProfile source,
            ConnectionProfile target,
            List<String> batch,
            MigrateKeysRequest request,
            Running job) {
        if (job.byValue) {
            return byValue(source, target, batch, request, job);
        }
        return transfers
                .transfer(source)
                .dumpMany(source, batch)
                .flatMap(
                        dumped -> {
                            Shaped<SerializedKey> shaped = renamed(dumped, request, job);
                            return transfers
                                    .transfer(target)
                                    .restoreMany(target, shaped.keys(), request.replace())
                                    .flatMap(
                                            outcomes -> {
                                                if (!refusedTheFormat(target, outcomes)) {
                                                    job.dropped.addAndGet(shaped.dropped());
                                                    return Uni.createFrom().item(outcomes);
                                                }
                                                // Everything this path decided is thrown away with
                                                // its outcomes, counts included.
                                                job.byValue = true;
                                                return byValue(source, target, batch, request, job);
                                            });
                        });
    }

    private Uni<List<RestoreOutcome>> byValue(
            ConnectionProfile source,
            ConnectionProfile target,
            List<String> batch,
            MigrateKeysRequest request,
            Running job) {
        return transfers
                .transfer(source)
                .readMany(source, batch)
                .flatMap(
                        copied -> {
                            Shaped<CopiedKey> shaped = renamedValues(copied, request, job);
                            job.dropped.addAndGet(shaped.dropped());
                            return transfers
                                    .transfer(target)
                                    .writeMany(target, shaped.keys(), request.replace());
                        });
    }

    /**
     * The same keys under the names they are to be written as.
     *
     * <p>Only the destination is renamed. The source is read, and deleted if that was asked for,
     * under the name it actually has — see {@link #afterRestore}, which is where getting this wrong
     * would have deleted a key nobody named.
     */
    private static Shaped<SerializedKey> renamed(
            List<SerializedKey> dumped, MigrateKeysRequest request, Running job) {
        if (!request.rewritesNames() && job.script == null) {
            return new Shaped<>(dumped, 0);
        }
        List<SerializedKey> shaped = new java.util.ArrayList<>(dumped.size());
        long dropped = 0;
        for (SerializedKey key : dumped) {
            KeyDecision decision = decide(key.key(), key.ttlMillis(), request, job);
            if (!decision.move()) {
                dropped++;
                continue;
            }
            shaped.add(new SerializedKey(decision.name(), decision.ttlMillis(), key.payload()));
        }
        return new Shaped<>(shaped, dropped);
    }

    /**
     * A batch after the shaping, and how much of it the shaping removed.
     *
     * <p>The count is handed back rather than added to the job here, because this runs on a path
     * that might be thrown away. A batch is shaped, offered to the target the fast way, and — if
     * the target will not read this store's dumps — shaped again and offered the slow way. Counting
     * inside the shaping counted those keys twice, which is exactly what a Redis 8 source and a
     * Valkey 9 target do to each other: their dump formats have diverged, so every migration
     * between them takes the fallback, and every key a script turned down was reported twice.
     */
    private record Shaped<T>(List<T> keys, long dropped) {}

    /**
     * The prefixes first, then the script.
     *
     * <p>In that order because the prefixes are the simple way of saying something the script can
     * also say, and a script that wanted to undo them could. The other order would mean a script
     * seeing names the person who wrote it never typed.
     */
    private static KeyDecision decide(
            String name, long ttlMillis, MigrateKeysRequest request, Running job) {
        String renamed = request.rewritesNames() ? request.destinationName(name) : name;
        return job.script == null
                ? KeyDecision.keep(renamed, ttlMillis)
                : job.script.decide(renamed, ttlMillis);
    }

    /** The same, for the path that copies values rather than dumps. */
    private static Shaped<CopiedKey> renamedValues(
            List<CopiedKey> copied, MigrateKeysRequest request, Running job) {
        if (!request.rewritesNames() && job.script == null) {
            return new Shaped<>(copied, 0);
        }
        List<CopiedKey> shaped = new java.util.ArrayList<>(copied.size());
        long dropped = 0;
        for (CopiedKey key : copied) {
            KeyDecision decision = decide(key.key(), key.ttlMillis(), request, job);
            if (!decision.move()) {
                dropped++;
                continue;
            }
            shaped.add(
                    new CopiedKey(decision.name(), key.type(), decision.ttlMillis(), key.writes()));
        }
        return new Shaped<>(shaped, dropped);
    }

    /** Whether the target turned the batch down because it cannot read the source's dumps. */
    private boolean refusedTheFormat(ConnectionProfile target, List<RestoreOutcome> outcomes) {
        KeyTransfer transfer = transfers.transfer(target);
        return outcomes.stream()
                .anyMatch(
                        outcome ->
                                outcome.isFailure()
                                        && transfer.isIncompatibleFormat(outcome.refusal()));
    }

    /**
     * Records what the target did with a batch and, when asked, removes what it accepted.
     *
     * <p>Only the keys the target accepted are deleted. Deleting one the target refused would be
     * the one way this feature could lose data, so a refusal leaves the source alone.
     */
    private Uni<Void> afterRestore(
            ConnectionProfile source,
            List<String> batch,
            List<RestoreOutcome> outcomes,
            MigrateKeysRequest request,
            Running job) {
        /*
         * What the target accepted, named the way the target names it — which is not the way the
         * source does when a migration renames as it copies.
         *
         * So the delete below works from the batch rather than from the outcomes: for every name
         * this batch read from the source, delete it if the name it was written as came back
         * accepted. Deleting `outcome.key()` directly is the version of this that removes a key
         * nobody asked about, on the source, because it happened to share a name with the
         * destination. It is the one way this feature could lose data.
         *
         * Two source keys renamed onto one destination are both deleted, and the second to be
         * written is the one that survives. That is what a rename onto a name already taken means,
         * and it is the caller's collision rather than a decision made here.
         */
        Set<String> acceptedNames =
                outcomes.stream()
                        .filter(RestoreOutcome::written)
                        .map(RestoreOutcome::key)
                        .collect(java.util.stream.Collectors.toSet());
        List<String> accepted =
                request.rewritesNames()
                        ? batch.stream()
                                .filter(
                                        name ->
                                                acceptedNames.contains(
                                                        request.destinationName(name)))
                                .toList()
                        : List.copyOf(acceptedNames);

        for (RestoreOutcome outcome : outcomes) {
            if (outcome.written()) {
                job.migrated.incrementAndGet();
            } else if (outcome.isFailure()) {
                job.failed.incrementAndGet();
                if (job.reason == null) {
                    job.reason = outcome.refusal();
                }
            } else {
                job.skipped.incrementAndGet();
            }
        }

        if (!request.deleteFromSource() || accepted.isEmpty()) {
            return Uni.createFrom().voidItem();
        }
        // The source's own name, not the destination's: this is the half of a move that empties a
        // server, and a migration that named where it was writing has not said anything about
        // where it is taking things from.
        return keys.delete(source.id, source.selectedDatabase, accepted, request.confirmSource())
                .invoke(result -> job.deleted.addAndGet(result.affected()))
                .replaceWithVoid();
    }

    private void finish(String id, MigrationJob.State state, Throwable failure) {
        Running job = running.remove(id);
        if (job == null) {
            // Cancelled, which already reported its own ending.
            return;
        }
        job.state = state;
        job.finishedAt = Instant.now();
        if (failure != null) {
            job.reason = failure.getMessage();
            LOG.errorf(failure, "Migration %s failed", id);
        }
        ended(job.snapshot());
    }

    /**
     * Announces an ending and writes it down.
     *
     * <p>The broadcast first: a page watching this job should not wait on a database write, and the
     * write is the one of the two that can fail without anybody being worse off — the row is a
     * record, and the record is worth less than the person watching being told.
     */
    private void ended(MigrationJob job) {
        publish(job);
        write(
                job,
                failure ->
                        LOG.errorf(
                                failure, "Could not write down how migration %s ended", job.id()));
    }

    /**
     * Publishes progress every so many batches.
     *
     * <p>A broadcast per batch is one per two hundred keys, which against a fast local target is
     * hundreds a second — more than a progress bar can use and more than the socket should carry.
     * The state a job ends in is always published, whatever the interval.
     */
    private void publishEvery(Running job) {
        long batches = job.batches.incrementAndGet();
        if (batches % progressEvery == 0) {
            publish(job.snapshot());
        }
        if (batches % CHECKPOINT_EVERY == 0) {
            checkpoint(job.snapshot());
        }
    }

    /**
     * Writes how far a running job has got, without letting that hold the job up.
     *
     * <p>Subscribed and forgotten: a checkpoint that fails costs a stale number on a row nobody is
     * reading yet, and a migration that stopped because its bookkeeping stopped would be a far
     * worse trade.
     */
    private void checkpoint(MigrationJob job) {
        write(job, failure -> LOG.debugf(failure, "Could not checkpoint migration %s", job.id()));
    }

    /**
     * Writes a snapshot of a job to its row, on a context of its own.
     *
     * <p>Its own context is the whole point. A migration walks on one context and writes from it at
     * every checkpoint and again at the end; a session belongs to a context, so two of those writes
     * overlapping is two operations on one session — which Hibernate reports as "Illegal pop() with
     * non-matching JdbcValuesSourceProcessingState", from the write that was the whole record of
     * what happened. A fresh context per write gives each one a session of its own, and the
     * repository refuses to un-finish a row that a late checkpoint would otherwise reopen.
     */
    private void write(MigrationJob job, java.util.function.Consumer<Throwable> onFailure) {
        OwnContext.run(
                vertx,
                () ->
                        repository
                                .record(job, leadership.instanceId())
                                .invoke(mine -> stopIfTakenOver(job.id(), mine))
                                .replaceWithVoid(),
                onFailure);
    }

    /**
     * Lets go of a job the row no longer says is ours.
     *
     * <p>The only way a running walk learns it has been taken over. A migration is handed on when
     * its instance stops writing checkpoints, which a hung walk does while its process is still
     * perfectly alive — so the walk that comes back has to find out it lost, and stop. Without this
     * the two of them would move the same keys and write over each other's counters.
     *
     * <p>Cancelled rather than marked, and nothing is recorded: the row belongs to somebody else
     * now, and writing an ending onto it is the exact mistake this is here to prevent.
     */
    private void stopIfTakenOver(String id, Boolean stillOurs) {
        if (Boolean.TRUE.equals(stillOurs)) {
            return;
        }
        Running job = running.remove(id);
        if (job == null) {
            return;
        }
        LOG.infof("Migration %s has been taken over by another instance; stopping here", id);
        if (job.cancellable != null) {
            job.cancellable.cancel();
        }
    }

    private void publish(MigrationJob job) {
        // Tagged with the source. A migration touches two targets and the person watching it
        // started it, so they can see both; naming one is what stops the progress of somebody
        // else's migration reaching a browser that can see neither.
        hub.broadcast(NotificationCategory.MIGRATION_PROGRESS, job.sourceConnectionId(), job);
    }

    /**
     * How many keyspaces this instance is currently walking.
     *
     * <p>The long work phase 42 taught to survive its instance, and the reason an instance can be a
     * bad one to restart right now.
     */
    @Override
    public Snapshot snapshot() {
        return Snapshot.ofJobs(running.size());
    }
}
