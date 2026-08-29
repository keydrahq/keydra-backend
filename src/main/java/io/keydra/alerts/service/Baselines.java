package io.keydra.alerts.service;

import io.keydra.alerts.entity.AlertBasis;
import io.keydra.alerts.service.AlertRegistry.Watched;
import io.keydra.common.vertx.OwnContext;
import io.keydra.engine.MetricsSample;
import io.keydra.monitoring.dto.MetricsHistory;
import io.keydra.monitoring.service.MetricsHistoryService;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * What a rule's metric read over an earlier window.
 *
 * <p>Held rather than asked for. A baseline is a fact about last week: asking the store for it on
 * every reading would be a query per rule every five seconds in exchange for a number that does not
 * move, and the evaluator runs on a timer where waiting on a query is exactly what it must not do.
 * So the evaluator reads what is here, and a stale entry starts a refresh that the next reading
 * will benefit from.
 *
 * <p>Null is a real answer and is treated as one everywhere: no store, a window older than anything
 * kept, or a target that was not being watched then. A rule whose baseline cannot be read is left
 * exactly as it was — the same rule phase 15 already follows for a missing reading, and for the
 * same reason. Absence is not a value.
 */
@ApplicationScoped
public class Baselines {

    private static final Logger LOG = Logger.getLogger(Baselines.class);

    /** One figure, and when this process worked it out. */
    private record Known(Double value, long computedAt) {}

    private final MetricsHistoryService history;
    private final Vertx vertx;
    private final Duration refreshAfter;

    private final Map<Long, Known> known = new ConcurrentHashMap<>();

    /** Rules with a refresh in flight, so a slow store does not collect a queue of them. */
    private final Set<Long> working = ConcurrentHashMap.newKeySet();

    @Inject
    Baselines(
            MetricsHistoryService history,
            Vertx vertx,
            @ConfigProperty(name = "keydra.alerts.baseline-refresh") Duration refreshAfter) {
        this.history = history;
        this.vertx = vertx;
        this.refreshAfter = refreshAfter;
    }

    /**
     * The baseline for a rule, or null when there is not one yet.
     *
     * <p>Does not wait: this is called from the evaluator, once per rule per reading. A missing or
     * stale figure starts the work and answers with what is held, which for the first few readings
     * after a restart is nothing at all.
     */
    public Double of(Watched rule) {
        if (rule.basis() != AlertBasis.BASELINE) {
            return null;
        }
        Known held = known.get(rule.id());
        if (held == null || isStale(held)) {
            refresh(rule);
        }
        return held == null ? null : held.value();
    }

    /** Forgets a rule's figure, because the window it was taken over may have just changed. */
    public void forget(Long ruleId) {
        known.remove(ruleId);
    }

    /**
     * Reads the window a rule compares against, and answers what the metric did over it.
     *
     * <p>Public so the interface can ask for one on demand — a rules list wants to say what the
     * comparison is against, and it can afford to wait where the evaluator cannot.
     */
    public Uni<Double> read(Watched rule) {
        Instant to = Instant.now().minusSeconds(rule.baselineOffsetSeconds());
        Instant from = to.minusSeconds(Math.max(1, rule.baselineWindowSeconds()));
        return history.between(rule.connectionId(), from, to, 1)
                .map(window -> average(rule, window))
                .onFailure()
                .invoke(
                        failure ->
                                LOG.debugf(
                                        failure,
                                        "Could not read the baseline for rule %d",
                                        rule.id()))
                .onFailure()
                .recoverWithItem((Double) null);
    }

    /**
     * The metric across a window, as one number.
     *
     * <p>One bucket where a store answered and every reading where memory did; averaging both is
     * the same arithmetic, and it is what makes "the same hour last week" a comparison with an hour
     * rather than with whatever happened to be recorded at one instant in it.
     */
    private static Double average(Watched rule, MetricsHistory window) {
        if (window.source() == MetricsHistory.Source.NONE || window.samples().isEmpty()) {
            return null;
        }
        double total = 0;
        int counted = 0;
        for (MetricsSample sample : window.samples()) {
            Double value = rule.metric().of(sample, null);
            if (value != null) {
                total += value;
                counted++;
            }
        }
        return counted == 0 ? null : total / counted;
    }

    private boolean isStale(Known held) {
        return System.nanoTime() - held.computedAt() > refreshAfter.toNanos();
    }

    private void refresh(Watched rule) {
        if (!working.add(rule.id())) {
            return;
        }
        OwnContext.run(
                vertx,
                () ->
                        read(rule)
                                .invoke(
                                        value -> {
                                            if (value != null) {
                                                known.put(
                                                        rule.id(),
                                                        new Known(value, System.nanoTime()));
                                            }
                                            working.remove(rule.id());
                                        })
                                .onFailure()
                                .invoke(ignored -> working.remove(rule.id())),
                failure -> {
                    working.remove(rule.id());
                    LOG.debugf(failure, "Could not refresh the baseline for rule %d", rule.id());
                });
    }
}
