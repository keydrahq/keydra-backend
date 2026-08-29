package io.keydra.telemetry.service;

import io.keydra.cluster.service.InstanceId;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.distribution.DistributionStatisticConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

/**
 * The one place a meter is named.
 *
 * <p>Not a wrapper for its own sake. A metric name and its labels are read by systems nobody in
 * this project controls and outlive the code that emits them — renaming one breaks a dashboard
 * somebody built a year ago — so the names live together where they can be seen together, and the
 * domains call a method that says what happened instead of inventing a string.
 *
 * <p>Labels are ids and outcomes, never names. A target's name is a fact about somebody's estate; a
 * series keeps its labels for as long as it exists and is read by whoever can read the scrape. An
 * id says the same thing to anybody entitled to resolve it, and does not grow the cardinality every
 * time a target is renamed.
 */
@ApplicationScoped
public class KeydraMeters {

    /** How many targets are in each state. Tagged by the state, not by which target. */
    private static final String TARGETS = "keydra.targets";

    /** How long it takes to read a target's vital signs, and how often that fails. */
    private static final String SAMPLE = "keydra.target.sample";

    private static final String SAMPLE_FAILURES = "keydra.target.sample.failures";

    private static final String SCHEDULE_RUNS = "keydra.schedule.runs";

    private static final String ALERT_EVENTS = "keydra.alert.events";

    private static final String ALERT_DELIVERIES = "keydra.alert.deliveries";

    private static final String BACKUPS = "keydra.backups";

    private static final String MIGRATIONS_RUNNING = "keydra.migrations.running";

    /** One where this instance is doing the work that happens once, and zero where it is not. */
    private static final String CHORES = "keydra.chores";

    /**
     * Everything this instance has asked of the targets it watches.
     *
     * <p>The main thing Keydra does, and the number the instances page has drawn since phase 41
     * without it being anywhere an operator could graph. Beside {@code keydra.target.sample}
     * because it is the same subject seen from the other end: how long a target takes to answer,
     * and how much it is being asked.
     */
    private static final String COMMANDS = "keydra.target.commands";

    private final MeterRegistry registry;

    /** On everything made here, and applied at creation because that is when a tag can be. */
    private final Tags instance;

    /**
     * The suppliers behind the gauges, held so they stay alive.
     *
     * <p>Micrometer keeps a weak reference to what a gauge reads, so that a meter cannot keep a
     * dead object in memory. The consequence for a supplier written as a lambda at the call site is
     * that nothing else refers to it, it is collected, and the gauge quietly starts reporting NaN —
     * a metric that exists, is named correctly, and says nothing. Holding them here is the whole
     * fix, and it belongs here rather than in every caller.
     */
    private final List<Supplier<Number>> held = new CopyOnWriteArrayList<>();

    @Inject
    KeydraMeters(MeterRegistry registry) {
        this.registry = registry;
        this.instance = Tags.of("keydra_instance", InstanceId.get());
        registry.config().meterFilter(percentilesFor(TIMED));
    }

    /** The timers worth having percentiles of: what somebody waited for. */
    private static final Set<String> TIMED = Set.of("http.server.requests", SAMPLE);

    /**
     * Asks for buckets on the timers that answer "how long".
     *
     * <p>Without them a timer publishes a count, a sum and a maximum, and a mean is the one summary
     * that hides exactly what a percentile is for: a handful of very slow requests inside a
     * comfortable average. The buckets cost a series each, which is why this names the timers
     * rather than switching it on for everything.
     *
     * <p>Registered here rather than produced as a bean, and that is not a preference: a {@code
     * MeterFilter} bean is asked for while Vert.x itself is being built, before the container will
     * hand anything out. This runs when this bean is created at startup, which is before the first
     * request and therefore before the meters it configures exist — filters only apply to meters
     * registered after them.
     */
    private static MeterFilter percentilesFor(Set<String> names) {
        return new MeterFilter() {
            @Override
            public DistributionStatisticConfig configure(
                    Meter.Id id, DistributionStatisticConfig config) {
                if (!names.contains(id.getName())) {
                    return config;
                }
                return DistributionStatisticConfig.builder()
                        .percentilesHistogram(true)
                        .build()
                        .merge(config);
            }
        };
    }

    // --- What is watched continuously ---------------------------------------

    /**
     * Follows how many targets are up and how many are down.
     *
     * <p>Gauges rather than counters because the question is "how many, now"; the supplier is read
     * at scrape time, so nothing here has to be kept in step with anything.
     */
    public void watchTargets(Supplier<Number> up, Supplier<Number> down) {
        held.add(up);
        held.add(down);
        Gauge.builder(TARGETS, up)
                .tags(instance.and("state", "up"))
                .description("Targets Keydra last reached")
                .register(registry);
        Gauge.builder(TARGETS, down)
                .tags(instance.and("state", "down"))
                .description("Targets Keydra last failed to reach")
                .register(registry);
    }

    public void watchMigrations(Supplier<Number> running) {
        held.add(running);
        Gauge.builder(MIGRATIONS_RUNNING, running)
                .tags(instance)
                .description("Key migrations this instance is walking")
                .register(registry);
    }

    /**
     * Follows whether this instance holds the chores.
     *
     * <p>The one number that makes the rest legible where more than one Keydra runs: a schedule
     * that did not fire is a question about the instance reporting one here.
     */
    public void watchChores(Supplier<Number> holding) {
        held.add(holding);
        Gauge.builder(CHORES, holding)
                .tags(instance)
                .description("Whether this instance does the work that happens once")
                .register(registry);
    }

    /**
     * Follows how much this instance has asked of the targets it watches.
     *
     * <p>A counter rather than a gauge, because the number only ever goes up and saying so is what
     * lets a scraper turn two readings into a rate without wondering whether it missed a reset. A
     * function counter rather than one incremented here: the count already exists, kept where the
     * commands actually leave, and a second tally would be a second answer to one question.
     *
     * <p>Untagged by target, deliberately. Which target was asked is a question the target's own
     * metrics answer; this is what this instance is doing, and a label per target would multiply
     * the series by the size of somebody's estate.
     */
    public void watchCommands(Supplier<Number> total) {
        held.add(total);
        FunctionCounter.builder(COMMANDS, total, supplier -> supplier.get().doubleValue())
                .tags(instance)
                .description("Commands this instance has sent to the targets it watches")
                .register(registry);
    }

    // --- What is recorded as it happens --------------------------------------

    public void sampled(Long connectionId, Duration took) {
        Timer.builder(SAMPLE)
                .tags(instance.and("connection", String.valueOf(connectionId)))
                .description("Time to read a target's vital signs")
                .register(registry)
                .record(took);
    }

    public void sampleFailed(Long connectionId) {
        counter(SAMPLE_FAILURES, Tags.of("connection", String.valueOf(connectionId))).increment();
    }

    public void scheduleRan(String outcome, boolean manual) {
        counter(SCHEDULE_RUNS, Tags.of("outcome", outcome, "manual", String.valueOf(manual)))
                .increment();
    }

    public void alertRaised(String kind) {
        counter(ALERT_EVENTS, Tags.of("kind", kind)).increment();
    }

    public void alertDelivered(String outcome) {
        counter(ALERT_DELIVERIES, Tags.of("outcome", outcome)).increment();
    }

    public void backupTaken(String outcome) {
        counter(BACKUPS, Tags.of("outcome", outcome)).increment();
    }

    private Counter counter(String name, Tags tags) {
        return registry.counter(name, instance.and(tags));
    }
}
