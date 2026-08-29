package io.keydra.monitoring.service;

import io.keydra.alerts.service.AlertEvaluator;
import io.keydra.connections.dto.ConnectionRemoved;
import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.service.ConnectionService;
import io.keydra.engine.EngineSelector;
import io.keydra.engine.KeySize;
import io.keydra.engine.MetricsSample;
import io.keydra.engine.ServerMetrics;
import io.keydra.events.dto.NotificationCategory;
import io.keydra.events.service.NotificationHub;
import io.keydra.monitoring.dto.BigKeysReport;
import io.keydra.monitoring.dto.MonitoringState;
import io.keydra.monitoring.dto.TargetSample;
import io.keydra.monitoring.sink.MetricsSink;
import io.keydra.monitoring.sink.RingSink;
import io.keydra.telemetry.service.KeydraMeters;
import io.quarkus.runtime.ShutdownEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Takes readings from targets that were asked to be watched.
 *
 * <p>Opt-in, and off by default. Sampling costs a round trip per target per interval whether anyone
 * is looking or not, and a Keydra holding fifty connection profiles should not quietly generate
 * fifty commands a second against production servers because someone once opened a dashboard.
 *
 * <p>Each reading is broadcast, so an open dashboard updates without polling — which is what the
 * phase's acceptance criterion asks for.
 */
@ApplicationScoped
public class MetricsSampler {

    private static final Logger LOG = Logger.getLogger(MetricsSampler.class);

    /** One target's sampler: its timer and who is holding it on. */
    private record Watch(long timerId, Set<Reason> reasons) {}

    /**
     * Why a target is being sampled.
     *
     * <p>A set rather than a switch, because two things ask for the same watch and neither knows
     * about the other. Without this, one person closing a dashboard stops a colleague's readings —
     * and, since phase 15, stops the rules that are the only reason anybody would be told about a
     * server filling up at four in the morning.
     */
    public enum Reason {
        /** Somebody is looking at it. */
        DASHBOARD,
        /** A rule is watching it, whether or not anybody is. */
        RULE
    }

    private final Vertx vertx;
    private final ConnectionService connections;
    private final EngineSelector engines;
    private final NotificationHub hub;
    private final AlertEvaluator alerts;
    private final RingSink ring;
    private final KeydraMeters meters;
    private final List<MetricsSink> sinks;
    private final Duration interval;
    private final Map<Long, Watch> watches = new ConcurrentHashMap<>();

    @Inject
    MetricsSampler(
            Vertx vertx,
            ConnectionService connections,
            EngineSelector engines,
            NotificationHub hub,
            AlertEvaluator alerts,
            RingSink ring,
            Instance<MetricsSink> sinks,
            KeydraMeters meters,
            @ConfigProperty(name = "keydra.monitoring.interval", defaultValue = "5s")
                    Duration interval) {
        this.vertx = vertx;
        this.connections = connections;
        this.engines = engines;
        this.hub = hub;
        this.alerts = alerts;
        this.ring = ring;
        this.meters = meters;
        // Every sink CDI knows about, which is how a deployment adds one: put it on the
        // classpath and configure it, and nothing here has to learn its name.
        this.sinks = sinks.stream().toList();
        this.interval = interval;
    }

    void onStop(@Observes ShutdownEvent event) {
        Set.copyOf(watches.keySet()).forEach(this::cancel);
    }

    /**
     * Starts sampling a target, and takes the first reading immediately.
     *
     * <p>Immediately because a dashboard that shows nothing for the first interval looks broken,
     * and the first reading is the one someone is waiting for.
     */
    public Uni<MonitoringState> start(Long connectionId, Reason reason) {
        Watch existing = watches.get(connectionId);
        if (existing != null) {
            existing.reasons().add(reason);
            return Uni.createFrom().item(state(connectionId));
        }

        Set<Reason> reasons = ConcurrentHashMap.newKeySet();
        reasons.add(reason);
        long timerId = vertx.setPeriodic(interval.toMillis(), id -> takeReading(connectionId));
        watches.put(connectionId, new Watch(timerId, reasons));

        return sample(connectionId)
                .invoke(reading -> record(connectionId, reading))
                .map(ignored -> state(connectionId))
                .onFailure()
                .recoverWithUni(
                        failure -> {
                            if (reason != Reason.RULE) {
                                // A target that cannot be read is not worth a timer, and the
                                // person who asked for one should hear why.
                                stop(connectionId, reason);
                                return Uni.createFrom().<MonitoringState>failure(failure);
                            }
                            // A rule asked for this watch, and a target that will not answer
                            // is the most likely thing it is watching for. The timer stays,
                            // and the silence is itself a reading.
                            alerts.onSilence(connectionId);
                            return Uni.createFrom().item(state(connectionId));
                        });
    }

    /**
     * Lets go of one reason for watching, and stops only when nobody is left holding it.
     *
     * @return whether this reason was holding it, which is what tells a caller its request meant
     *     anything
     */
    public boolean stop(Long connectionId, Reason reason) {
        Watch watch = watches.get(connectionId);
        if (watch == null || !watch.reasons().remove(reason)) {
            return false;
        }
        if (watch.reasons().isEmpty()) {
            cancel(connectionId);
        }
        return true;
    }

    /** Whether a rule is keeping this target sampled. */
    public boolean heldByRule(Long connectionId) {
        Watch watch = watches.get(connectionId);
        return watch != null && watch.reasons().contains(Reason.RULE);
    }

    /**
     * Stops watching a target for good, whoever was holding it.
     *
     * <p>For a profile that has been deleted rather than a dashboard that has been closed: a timer
     * left running against a target nobody can name any more asks a server for its statistics every
     * five seconds until the instance is restarted.
     */
    void onRemoved(@Observes ConnectionRemoved removed) {
        cancel(removed.id());
    }

    private void cancel(Long connectionId) {
        Watch watch = watches.remove(connectionId);
        if (watch == null) {
            return;
        }
        vertx.cancelTimer(watch.timerId());
        announce(connectionId, false);
    }

    public MonitoringState state(Long connectionId) {
        Watch watch = watches.get(connectionId);
        return new MonitoringState(
                watch != null,
                heldByRule(connectionId),
                (int) interval.toSeconds(),
                // From the sink rather than from the watch: closing a dashboard and opening it
                // again should not start the chart from nothing.
                ring.recent(connectionId),
                sinks.stream().anyMatch(MetricsSink::isDurable));
    }

    private void takeReading(Long connectionId) {
        // Timed from here rather than around the call to the store, because what somebody
        // watching a graph of this wants to know is what a reading costs — dialling, waiting
        // and parsing included.
        long startedAt = System.nanoTime();
        sample(connectionId)
                .subscribe()
                .with(
                        reading -> {
                            meters.sampled(
                                    connectionId, Duration.ofNanos(System.nanoTime() - startedAt));
                            record(connectionId, reading);
                        },
                        failure -> {
                            meters.sampleFailed(connectionId);
                            // A target going down mid-watch is expected; the health monitor
                            // reports that, and the sampler has nothing to add to the chart.
                            // The rules are told, though: silence is what one of them is for.
                            LOG.debugf(failure, "No reading from connection %d", connectionId);
                            alerts.onSilence(connectionId);
                        });
    }

    private void record(Long connectionId, MetricsSample reading) {
        // Read before the new one goes in: the rates are differences between two readings,
        // and after the write the previous one is this one.
        MetricsSample previous = ring.latest(connectionId);
        // Every sink, and none of them may hold this up: what a reading is written to is a
        // deployment's decision, and a slow store must not become a sampler that stopped.
        sinks.forEach(sink -> sink.write(connectionId, reading));
        hub.broadcast(
                NotificationCategory.METRICS_SAMPLE,
                connectionId,
                Map.of("connectionId", connectionId, "sample", reading));
        alerts.onReading(connectionId, reading, previous);
    }

    private Uni<MetricsSample> sample(Long connectionId) {
        return withMetrics(connectionId, (profile, metrics) -> metrics.sample(profile));
    }

    /**
     * One reading from every target the caller can see, taken together.
     *
     * <p>Two phases, and the split is the point. The profiles are loaded first, one after another,
     * because loading one is a database read and a reactive Hibernate session serves a single
     * statement at a time. The readings are then taken all at once, because each is a round trip to
     * a different server and those have nothing to do with each other — an estate of twenty targets
     * answers in the time the slowest one takes rather than the sum of twenty.
     *
     * <p>A target that will not answer comes back as a reading of nothing rather than failing the
     * lot. An overview showing nineteen servers and a gap is useful; an overview showing an error
     * because the twentieth is down is not — and that it is down is the one thing the page already
     * says in the column beside it.
     */
    public Uni<List<TargetSample>> fleet(List<Long> connectionIds) {
        if (connectionIds.isEmpty()) {
            return Uni.createFrom().item(List.of());
        }
        return loadEach(connectionIds)
                .flatMap(
                        profiles ->
                                Uni.join()
                                        .all(profiles.stream().map(this::readingOf).toList())
                                        .andCollectFailures());
    }

    /** The profiles, one after another: a session serves one statement at a time. */
    private Uni<List<ConnectionProfile>> loadEach(List<Long> connectionIds) {
        Uni<List<ConnectionProfile>> loaded = Uni.createFrom().item(new ArrayList<>());
        for (Long id : connectionIds) {
            loaded =
                    loaded.flatMap(
                            soFar ->
                                    connections
                                            .load(id)
                                            .map(
                                                    profile -> {
                                                        soFar.add(profile);
                                                        return soFar;
                                                    })
                                            // A profile that cannot be loaded is one target
                                            // missing from a summary, not a summary that failed.
                                            .onFailure()
                                            .recoverWithItem(soFar));
        }
        return loaded;
    }

    private Uni<TargetSample> readingOf(ConnectionProfile profile) {
        return engines.forProfile(profile)
                .metrics()
                .map(metrics -> metrics.sample(profile))
                .orElseGet(() -> Uni.createFrom().nullItem())
                .onFailure()
                .recoverWithItem(() -> null)
                .map(sample -> new TargetSample(profile.id, sample));
    }

    /** Runs something against the target's metrics, or fails plainly when it reports none. */
    public <T> Uni<T> withMetrics(
            Long connectionId,
            java.util.function.BiFunction<ConnectionProfile, ServerMetrics, Uni<T>> work) {
        return connections
                .load(connectionId)
                .flatMap(
                        profile ->
                                engines.forProfile(profile)
                                        .metrics()
                                        .map(metrics -> work.apply(profile, metrics))
                                        .orElseGet(
                                                () ->
                                                        Uni.createFrom()
                                                                .failure(
                                                                        new UnsupportedOperationException(
                                                                                "This target"
                                                                                    + " reports no"
                                                                                    + " statistics"))));
    }

    /**
     * Measures a sample of the keyspace and ranks it.
     *
     * <p>Not scheduled: measuring costs a round trip per key, so it runs when someone asks and says
     * how much of the keyspace it looked at.
     */
    public Uni<BigKeysReport> biggestKeys(Long connectionId, int sampleSize, int top) {
        return withMetrics(
                connectionId,
                (profile, metrics) ->
                        metrics.measureKeys(profile, sampleSize)
                                .collect()
                                .asList()
                                .map(
                                        measured -> {
                                            long total =
                                                    measured.stream()
                                                            .mapToLong(KeySize::bytes)
                                                            .sum();
                                            List<KeySize> largest =
                                                    measured.stream()
                                                            .sorted(
                                                                    Comparator.comparingLong(
                                                                                    KeySize::bytes)
                                                                            .reversed())
                                                            .limit(top)
                                                            .toList();
                                            return new BigKeysReport(
                                                    measured.size(), total, largest);
                                        }));
    }

    private void announce(Long connectionId, boolean enabled) {
        hub.broadcast(
                NotificationCategory.MONITORING_CHANGED,
                connectionId,
                Map.of("connectionId", connectionId, "enabled", enabled));
    }
}
