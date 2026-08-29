package io.keydra.alerts.service;

import io.keydra.alerts.entity.AlertBasis;
import io.keydra.alerts.entity.AlertMetric;
import io.keydra.alerts.entity.AlertRule;
import io.keydra.alerts.entity.AlertState;
import io.keydra.alerts.entity.Comparison;
import jakarta.enterprise.context.ApplicationScoped;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * The rules as the evaluator sees them, and where each one currently stands.
 *
 * <p>In memory and made of plain data, for the reason the connection registry gives for the same
 * choice: this is read from a timer thread on every reading, and a database query per target per
 * five seconds to fetch rows that change when somebody edits them is a query nobody needed. The
 * rows remain the truth; this is what the truth looked like at the last write.
 *
 * <p>The live state is here rather than on the row because it is a fact about the last few minutes.
 * A restart forgets it and learns it again within each rule's own duration — which is exactly what
 * would happen if the readings had only just started, and is the honest thing for a process that
 * was not running to say.
 */
@ApplicationScoped
public class AlertRegistry {

    /**
     * A rule, flattened.
     *
     * <p>A record and not the entity: the evaluator runs outside any session, and an entity read
     * back there is a detached object waiting to be a lazy-loading exception.
     */
    public record Watched(
            Long id,
            String name,
            Long connectionId,
            AlertMetric metric,
            Comparison comparison,
            AlertBasis basis,
            double threshold,
            int baselineWindowSeconds,
            int baselineOffsetSeconds,
            int forSeconds,
            /** Every place this rule announces itself; empty is the hub alone. */
            java.util.List<Long> deliveryIds) {

        public static Watched of(AlertRule rule) {
            return new Watched(
                    rule.id,
                    rule.name,
                    rule.connectionId,
                    rule.metric,
                    rule.comparison,
                    rule.basis,
                    rule.threshold,
                    rule.baselineWindowSeconds,
                    rule.baselineOffsetSeconds,
                    rule.forSeconds,
                    rule.deliveryIds == null ? java.util.List.of() : rule.deliveryIds);
        }

        /** Whether this rule is written against the past rather than against a number. */
        public boolean comparesWithThePast() {
            return basis == AlertBasis.BASELINE;
        }
    }

    /**
     * Where a rule stands and what it last saw.
     *
     * @param since when it entered this state, which is what the duration is measured from
     * @param reading the last reading, or null when the metric could not be read
     * @param readAt when that reading was taken, so an interface can say how fresh it is
     */
    public record Live(AlertState state, Instant since, Double reading, Instant readAt) {

        static final Live QUIET = new Live(AlertState.OK, null, null, null);

        Live seeing(Double reading, Instant at) {
            return new Live(state, since, reading, at);
        }
    }

    private final Map<Long, Watched> rules = new ConcurrentHashMap<>();
    private final Map<Long, Live> live = new ConcurrentHashMap<>();

    /** Takes a rule as it now is; a disabled one is simply not watched. */
    public void register(AlertRule rule) {
        if (!rule.enabled) {
            unregister(rule.id);
            return;
        }
        Watched incoming = Watched.of(rule);
        if (incoming.equals(rules.get(rule.id))) {
            // The same rule, read again. This happens on every re-read of the table — which is
            // how an instance learns what another one wrote — and a rule waiting out its
            // duration must not have that duration reset by being looked at.
            return;
        }
        rules.put(rule.id, incoming);
        // Not the live state: a rule that was firing and has been edited is a rule whose
        // threshold may have moved, and carrying the old verdict across the edit would
        // announce a change that never happened. The next reading decides again.
        live.remove(rule.id);
    }

    /**
     * Forgets every rule outside this set.
     *
     * <p>What makes a re-read of the table a reconciliation rather than an accumulation: a rule
     * deleted or disabled on another instance is gone from the rows, and this is where that absence
     * is noticed.
     */
    public void retainOnly(Set<Long> ruleIds) {
        List.copyOf(rules.keySet()).stream()
                .filter(id -> !ruleIds.contains(id))
                .forEach(this::unregister);
    }

    public void unregister(Long ruleId) {
        rules.remove(ruleId);
        live.remove(ruleId);
    }

    /** The rules watching one target, which is what a reading is measured against. */
    public List<Watched> forConnection(Long connectionId) {
        return rules.values().stream()
                .filter(rule -> rule.connectionId().equals(connectionId))
                .toList();
    }

    /** Every target with at least one rule watching it. */
    public Set<Long> connections() {
        return rules.values().stream().map(Watched::connectionId).collect(Collectors.toSet());
    }

    /** How many rules watch one target, which decides whether the watch is still needed. */
    public long countFor(Long connectionId) {
        return rules.values().stream()
                .filter(rule -> rule.connectionId().equals(connectionId))
                .count();
    }

    public Live live(Long ruleId) {
        return live.getOrDefault(ruleId, Live.QUIET);
    }

    public void update(Long ruleId, Live state) {
        live.put(ruleId, state);
    }
}
