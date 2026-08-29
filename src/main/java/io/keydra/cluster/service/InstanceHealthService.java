package io.keydra.cluster.service;

import io.keydra.authz.service.ProviderReachability;
import io.keydra.backup.service.DestinationReachability;
import io.keydra.cluster.dto.ClusterDtos.DependencyState;
import io.keydra.cluster.dto.ClusterDtos.DependencyState.Reached;
import io.keydra.cluster.dto.ClusterDtos.InstanceHealth;
import io.keydra.cluster.dto.ClusterDtos.InstanceSummary;
import io.keydra.cluster.entity.KeydraInstance;
import io.keydra.cluster.entity.ReachabilityCheck;
import io.keydra.cluster.persistence.LeaseRepository;
import io.keydra.cluster.persistence.ReachabilityRepository;
import io.keydra.connections.dto.ConnectionState;
import io.keydra.connections.registry.ConnectionRegistry;
import io.keydra.store.service.KeydraStore;
import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * How Keydra itself is doing.
 *
 * <p>Every target has a page saying how it is arranged and whether anything is wrong with it.
 * Keydra had none — which is a strange gap in a thing whose job is watching servers, and the one
 * that bites on the morning somebody asks why an alert did not fire and there is nowhere to see
 * that the instance holding the chores has been gone since three.
 *
 * <p>Two halves. Who is running, from the roster {@link InstanceRegistry} keeps; and everything
 * they reach — which is a longer list than it first looks. Keydra talks to a database, a shared
 * store, a metrics store, an identity provider or several, a mail server, however many backup
 * destinations and alert channels somebody has set up, whatever tunnels are open, and every target
 * in the catalog. A page that showed three of those and called it the picture would be a page that
 * is quietly wrong about what this installation depends on.
 */
@ApplicationScoped
public class InstanceHealthService {

    /**
     * How long a probe is given before it counts as unreachable.
     *
     * <p>Short. A page that waits as long as a dependency is willing to take is a page that hangs
     * when the thing it is reporting on hangs, which is precisely when somebody is reading it.
     */
    private static final Duration TIMEOUT = Duration.ofSeconds(2);

    private final InstanceRegistry instances;
    private final Leadership leadership;
    private final LeaseRepository leases;
    private final InstanceWatch watch;
    private final io.keydra.common.config.DeploymentChecks checks;
    private final ReachabilityRepository reachability;
    private final KeydraStore store;
    private final ConnectionRegistry targets;
    private final Optional<String> storeUrl;
    private final boolean clickHouseEnabled;
    private final Optional<String> mailHost;
    private final Optional<String> tracesEndpoint;

    @Inject
    InstanceHealthService(
            InstanceRegistry instances,
            Leadership leadership,
            LeaseRepository leases,
            InstanceWatch watch,
            io.keydra.common.config.DeploymentChecks checks,
            ReachabilityRepository reachability,
            KeydraStore store,
            ConnectionRegistry targets,
            @ConfigProperty(name = "keydra.store.url") Optional<String> storeUrl,
            @ConfigProperty(name = "keydra.metrics.clickhouse.enabled", defaultValue = "false")
                    boolean clickHouseEnabled,
            @ConfigProperty(name = "keydra.mail.host") Optional<String> mailHost,
            @ConfigProperty(name = "quarkus.otel.exporter.otlp.traces.endpoint")
                    Optional<String> tracesEndpoint) {
        this.instances = instances;
        this.leadership = leadership;
        this.leases = leases;
        this.watch = watch;
        this.checks = checks;
        this.reachability = reachability;
        this.store = store;
        this.targets = targets;
        this.storeUrl = storeUrl;
        this.clickHouseEnabled = clickHouseEnabled;
        this.mailHost = mailHost;
        this.tracesEndpoint = tracesEndpoint;
    }

    /**
     * The roster alone: who is running and what each of them is holding.
     *
     * <p>Separate from {@link #health()} because the two cost very different things. The full
     * answer probes the database and the stores with a two-second ceiling each, which is right for
     * a page opened because something might be wrong and wrong for a page that merely wants to say
     * which instances hold this target — that one would be making three outbound probes every few
     * seconds for a line of text.
     */
    @WithSession
    public Uni<List<InstanceSummary>> roster() {
        return leadership
                .holder()
                .onFailure()
                .recoverWithItem((String) null)
                .flatMap(holder -> instances.roster().map(rows -> summarise(rows, holder)));
    }

    @WithSession
    public Uni<InstanceHealth> health() {
        return roster().flatMap(
                        summaries ->
                                dependencies()
                                        .flatMap(
                                                deps ->
                                                        choresStoppedSince()
                                                                .map(
                                                                        since ->
                                                                                new InstanceHealth(
                                                                                        summaries,
                                                                                        deps,
                                                                                        since,
                                                                                        checks
                                                                                                .notes()))));
    }

    /**
     * When the chores stopped, or null in every ordinary case.
     *
     * <p>The same threshold {@code InstanceWatch} announces on, asked the same way, because a page
     * saying one thing and a message saying another about one fact is how somebody learns to
     * believe neither. A lease that has never existed answers null: a fresh installation has none
     * until its first beat.
     */
    private Uni<Instant> choresStoppedSince() {
        Instant tooLongAgo = Instant.now().minusSeconds(watch.choresStoppedAfterSeconds());
        return leases.lapsedAt(Leadership.CHORES)
                .map(
                        lapsedAt ->
                                lapsedAt != null && lapsedAt.isBefore(tooLongAgo) ? lapsedAt : null)
                .onFailure()
                .recoverWithItem((Instant) null);
    }

    /** The roster rows as the page reads them, with the two facts only this instance can add. */
    private List<InstanceSummary> summarise(List<KeydraInstance> rows, String holder) {
        String self = instances.self();
        return rows.stream()
                .map(
                        row ->
                                new InstanceSummary(
                                        row.id,
                                        row.version,
                                        row.commit,
                                        row.startedAt,
                                        row.lastSeenAt,
                                        row.id.equals(holder),
                                        row.id.equals(self),
                                        row.published,
                                        row.received,
                                        row.commands,
                                        row.sockets,
                                        row.streams,
                                        row.jobs,
                                        row.watching == null ? List.of() : row.watching,
                                        row.draining,
                                        row.lastSeenAt.isAfter(
                                                Instant.now()
                                                        .minusSeconds(
                                                                instances.absentAfterSeconds()))))
                .toList();
    }

    /**
     * Everything Keydra reaches, in the order somebody would worry about it.
     *
     * <p>The two that are probed come first because they are the two Keydra cannot work without;
     * the rest are counted rather than probed. Counting is not laziness — an identity provider is
     * reached when somebody signs in and a backup destination when a backup runs, so "reachable
     * right now" is a question about a moment nobody is in. What a page can honestly say about
     * those is how many there are and whether any of them is switched off, and it says that.
     */
    private Uni<List<DependencyState>> dependencies() {
        return database()
                .flatMap(db -> sharedStore().map(shared -> new ArrayList<>(List.of(db, shared))))
                .flatMap(
                        found ->
                                counted()
                                        .map(
                                                rest -> {
                                                    found.add(metricsStore());
                                                    found.addAll(rest);
                                                    found.add(targetsState());
                                                    found.add(traces());
                                                    return (List<DependencyState>) found;
                                                }));
    }

    /**
     * The things there can be several of, counted in one pass through the database.
     *
     * <p>Two of them are more than counted. What an identity provider and a backup destination said
     * when they were last asked is read from the table the checker writes, rather than asked here:
     * ten people watching this page would otherwise be ten times the outbound traffic of one, aimed
     * at somebody else's service, which is what phase 40 refused and phase 49 kept refusing.
     */
    private Uni<List<DependencyState>> counted() {
        return reachability.all().flatMap(this::counted);
    }

    private Uni<List<DependencyState>> counted(List<ReachabilityCheck> answers) {
        return count("from IdentityProviderConfig")
                .flatMap(
                        providers ->
                                count("from IdentityProviderConfig where enabled = true")
                                        .map(
                                                enabled ->
                                                        DependencyState.many(
                                                                "identity-providers",
                                                                "Identity providers",
                                                                "OpenID Connect",
                                                                providers.intValue(),
                                                                enabled.intValue(),
                                                                reached(
                                                                        answers,
                                                                        ProviderReachability
                                                                                .KIND))))
                .flatMap(
                        idp ->
                                count("from BackupDestination")
                                        .flatMap(
                                                places ->
                                                        count(
                                                                        "from BackupDestination"
                                                                                + " where enabled ="
                                                                                + " true")
                                                                .map(
                                                                        live ->
                                                                                List.of(
                                                                                        idp,
                                                                                        DependencyState
                                                                                                .many(
                                                                                                        "backup-destinations",
                                                                                                        "Backup"
                                                                                                            + " destinations",
                                                                                                        "object"
                                                                                                            + " store"
                                                                                                            + " or file",
                                                                                                        places
                                                                                                                .intValue(),
                                                                                                        live
                                                                                                                .intValue(),
                                                                                                        reached(
                                                                                                                answers,
                                                                                                                DestinationReachability
                                                                                                                        .KIND))))))
                .flatMap(
                        sofar ->
                                count("from AlertDelivery")
                                        .flatMap(
                                                channels ->
                                                        count(
                                                                        "from AlertDelivery where"
                                                                                + " enabled = true")
                                                                .map(
                                                                        live -> {
                                                                            List<DependencyState>
                                                                                    all =
                                                                                            new ArrayList<>(
                                                                                                    sofar);
                                                                            all.add(
                                                                                    DependencyState
                                                                                            .many(
                                                                                                    "alert-channels",
                                                                                                    "Alert"
                                                                                                        + " channels",
                                                                                                    "mail,"
                                                                                                        + " chat"
                                                                                                        + " and webhooks",
                                                                                                    channels
                                                                                                            .intValue(),
                                                                                                    live
                                                                                                            .intValue()));
                                                                            all.add(mailServer());
                                                                            return all;
                                                                        })))
                .flatMap(
                        sofar ->
                                count("from SshTunnel")
                                        .map(
                                                tunnels -> {
                                                    List<DependencyState> all =
                                                            new ArrayList<>(sofar);
                                                    all.add(
                                                            DependencyState.many(
                                                                    "ssh-tunnels",
                                                                    "SSH tunnels",
                                                                    "ssh",
                                                                    tunnels.intValue(),
                                                                    tunnels.intValue()));
                                                    return all;
                                                }));
    }

    /** The database, asked the cheapest question there is. */
    private Uni<DependencyState> database() {
        return Panache.getSession()
                .flatMap(session -> session.createNativeQuery("select 1").getSingleResult())
                .ifNoItem()
                .after(TIMEOUT)
                .fail()
                .map(
                        ignored ->
                                DependencyState.one(
                                        "database", "Database", "PostgreSQL", true, true, null))
                .onFailure()
                .recoverWithItem(
                        failure ->
                                DependencyState.one(
                                        "database",
                                        "Database",
                                        "PostgreSQL",
                                        true,
                                        false,
                                        failure.toString()));
    }

    /**
     * Keydra's own store, which most deployments do not have.
     *
     * <p>Not having one is the default and is not a fault: the in-process cache is what a single
     * instance runs on. Saying "not configured" rather than drawing it red is the difference
     * between a page that reports and one that nags.
     */
    private Uni<DependencyState> sharedStore() {
        if (!store.isShared()) {
            return Uni.createFrom()
                    .item(
                            DependencyState.one(
                                            "shared-store",
                                            "Shared store",
                                            "in-process",
                                            false,
                                            true,
                                            null)
                                    .withNote("shared-store-local"));
        }
        // ping rather than get: every read on the store recovers from a failure by design — a
        // cache that cannot be reached is a cache miss — so a probe built on one reports a store
        // that has been stopped as reachable. It did, and this page is the one place that would
        // have been confidently wrong about it.
        return store.ping()
                .ifNoItem()
                .after(TIMEOUT)
                .fail()
                .map(
                        ignored ->
                                DependencyState.one(
                                        "shared-store",
                                        "Shared store",
                                        kindOfStore(),
                                        true,
                                        true,
                                        null))
                .onFailure()
                .recoverWithItem(
                        failure ->
                                DependencyState.one(
                                        "shared-store",
                                        "Shared store",
                                        kindOfStore(),
                                        true,
                                        false,
                                        failure.toString()));
    }

    /**
     * The metrics store, reported from configuration rather than probed.
     *
     * <p>Deliberately: the sink batches and writes on its own timer, so "reachable right now" is
     * not the question — whether this deployment has one at all is. Probing it here would also mean
     * an HTTP round trip on a page load for something that is written to every ten seconds anyway.
     */
    private DependencyState metricsStore() {
        return DependencyState.one(
                        "metrics-history",
                        "Metrics history",
                        "ClickHouse",
                        clickHouseEnabled,
                        clickHouseEnabled,
                        null)
                .withNote(clickHouseEnabled ? null : "metrics-history-off");
    }

    /** Where mail goes, when there is anywhere for it to go. */
    private DependencyState mailServer() {
        boolean configured = mailHost.filter(host -> !host.isBlank()).isPresent();
        return DependencyState.one(
                        "mail",
                        "Mail",
                        configured ? mailHost.orElse("SMTP") : "SMTP",
                        configured,
                        configured,
                        null)
                .withNote(configured ? null : "mail-off");
    }

    /** Where traces and log records go, which is a dependency even though nothing waits on it. */
    private DependencyState traces() {
        boolean configured = tracesEndpoint.filter(url -> !url.isBlank()).isPresent();
        return DependencyState.one(
                        "observability", "Observability", "OTLP", configured, configured, null)
                .withNote(configured ? null : "observability-off");
    }

    /**
     * The servers this Keydra is for.
     *
     * <p>On this page because they are connections Keydra holds, and a picture of what an
     * installation reaches that left out the reason it exists would be a strange picture. The
     * numbers come from the registry rather than from a count of rows: what matters is how many are
     * answering, and only the registry knows that.
     */
    private DependencyState targetsState() {
        int watched = targets.registeredIds().size();
        int up = (int) targets.countInState(ConnectionState.UP);
        return DependencyState.many(
                        "targets", "Targets", "Redis, Valkey, Aerospike, TiKV…", watched, up)
                .withNote(watched == 0 ? "targets-none" : null);
    }

    /**
     * What the last walk found out about one kind of thing.
     *
     * <p>The oldest answer's time rather than the newest: the reading is as stale as its stalest
     * part, and saying otherwise would be picking the flattering number.
     */
    private static Reached reached(List<ReachabilityCheck> answers, String kind) {
        List<ReachabilityCheck> mine =
                answers.stream().filter(row -> kind.equals(row.kind)).toList();
        if (mine.isEmpty()) {
            return null;
        }
        return new Reached(
                mine.stream()
                        .map(row -> row.checkedAt)
                        .min(java.time.Instant::compareTo)
                        .orElse(null),
                mine.size(),
                (int) mine.stream().filter(row -> row.ok).count());
    }

    private Uni<Long> count(String from) {
        return Panache.getSession()
                .flatMap(
                        session ->
                                session.createQuery("select count(*) " + from, Long.class)
                                        .getSingleResult());
    }

    /**
     * The scheme only. A store URL can carry a password, and this is read by whoever opens a page.
     */
    private String kindOfStore() {
        return storeUrl.map(url -> url.split(":", 2)[0]).map(String::toUpperCase).orElse("shared");
    }
}
