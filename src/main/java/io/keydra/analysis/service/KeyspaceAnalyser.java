package io.keydra.analysis.service;

import io.keydra.analysis.dto.ExpiryBand;
import io.keydra.analysis.dto.KeyspaceReport;
import io.keydra.analysis.dto.NamespaceUsage;
import io.keydra.analysis.dto.TypeUsage;
import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.service.ConnectionService;
import io.keydra.engine.Database;
import io.keydra.engine.EngineSelector;
import io.keydra.engine.KeySize;
import io.keydra.engine.ServerMetrics;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Where a target's memory went.
 *
 * <p>Every Redis console can tell somebody which single key is the biggest. That is rarely the
 * question: a server fills up because one namespace quietly grew, or because a cache was written
 * without expiries, and neither shows up in a list of the top ten keys. This answers those two.
 *
 * <p>Measured over a bounded sample. MEMORY USAGE costs a round trip per key, so a full census of a
 * large keyspace would cost more than the answer is worth — and the answer is a proportion, which a
 * fair sample gives just as well. The report says how many keys it looked at and how many there
 * are, so nobody has to guess how much to trust it.
 */
@ApplicationScoped
public class KeyspaceAnalyser {

    /** The delimiter people already use to namespace keys. */
    private static final String DELIMITER = ":";

    /**
     * How many individual keys are worth naming; beyond that it is a list rather than a finding.
     */
    private static final int LARGEST = 20;

    /** How many namespaces and types are worth listing before the rest become "everything else". */
    private static final int GROUPS = 20;

    private static final String OTHER = "…";

    private final ConnectionService connections;
    private final EngineSelector engines;
    private final int defaultSample;

    @Inject
    KeyspaceAnalyser(
            ConnectionService connections,
            EngineSelector engines,
            @ConfigProperty(name = "keydra.analysis.sample-size", defaultValue = "5000")
                    int defaultSample) {
        this.connections = connections;
        this.engines = engines;
        this.defaultSample = defaultSample;
    }

    public Uni<KeyspaceReport> analyse(Long connectionId, Integer database, Integer sampleSize) {
        int sample = sampleSize == null || sampleSize <= 0 ? defaultSample : sampleSize;
        return connections
                .load(connectionId, database)
                .flatMap(profile -> analyse(profile, sample));
    }

    private Uni<KeyspaceReport> analyse(ConnectionProfile profile, int sample) {
        ServerMetrics metrics =
                engines.forProfile(profile)
                        .metrics()
                        .orElseThrow(
                                () ->
                                        new UnsupportedOperationException(
                                                "This engine cannot measure its keys"));

        return metrics.measureKeys(profile, sample)
                .collect()
                .asList()
                .flatMap(
                        measured ->
                                // How many there really are, so the sample can be judged: a
                                // proportion measured over five thousand of five thousand is a
                                // census, and over five thousand of ten million is an estimate.
                                metrics.databases(profile)
                                        .map(
                                                databases ->
                                                        report(measured, count(databases, profile)))
                                        .onFailure()
                                        .recoverWithItem(() -> report(measured, measured.size())));
    }

    private static long count(List<Database> databases, ConnectionProfile profile) {
        return databases.stream()
                .filter(database -> database.index() == profile.effectiveDatabase())
                .mapToLong(Database::keys)
                .findFirst()
                .orElse(0);
    }

    private static KeyspaceReport report(List<KeySize> measured, long keysInDatabase) {
        return new KeyspaceReport(
                measured.size(),
                keysInDatabase,
                measured.stream().mapToLong(KeySize::bytes).sum(),
                namespaces(measured),
                types(measured),
                expiry(measured),
                largest(measured));
    }

    /**
     * The memory grouped by the first segment of the key.
     *
     * <p>The long tail is collapsed rather than listed: a keyspace with four thousand distinct
     * prefixes has none worth naming, and the point of the list is the handful that dominate.
     */
    private static List<NamespaceUsage> namespaces(List<KeySize> measured) {
        Map<String, long[]> totals = new LinkedHashMap<>();
        for (KeySize key : measured) {
            String prefix = prefixOf(key.key());
            long[] counts = totals.computeIfAbsent(prefix, ignored -> new long[3]);
            counts[0]++;
            counts[1] += key.bytes();
            if (key.ttlMillis() == KeySize.NO_EXPIRY) {
                counts[2]++;
            }
        }

        List<NamespaceUsage> all =
                totals.entrySet().stream()
                        .map(
                                entry ->
                                        new NamespaceUsage(
                                                entry.getKey(),
                                                entry.getValue()[0],
                                                entry.getValue()[1],
                                                entry.getValue()[2]))
                        .sorted(Comparator.comparingLong(NamespaceUsage::bytes).reversed())
                        .toList();

        if (all.size() <= GROUPS) {
            return all;
        }
        List<NamespaceUsage> top = new ArrayList<>(all.subList(0, GROUPS));
        List<NamespaceUsage> rest = all.subList(GROUPS, all.size());
        top.add(
                new NamespaceUsage(
                        OTHER,
                        rest.stream().mapToLong(NamespaceUsage::keys).sum(),
                        rest.stream().mapToLong(NamespaceUsage::bytes).sum(),
                        rest.stream().mapToLong(NamespaceUsage::neverExpires).sum()));
        return top;
    }

    /** The first segment, or the whole key when it carries no delimiter. */
    private static String prefixOf(String key) {
        int at = key.indexOf(DELIMITER);
        return at <= 0 ? key : key.substring(0, at);
    }

    private static List<TypeUsage> types(List<KeySize> measured) {
        Map<String, long[]> totals = new LinkedHashMap<>();
        for (KeySize key : measured) {
            long[] counts = totals.computeIfAbsent(key.type(), ignored -> new long[2]);
            counts[0]++;
            counts[1] += key.bytes();
        }
        return totals.entrySet().stream()
                .map(
                        entry ->
                                new TypeUsage(
                                        entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                .sorted(Comparator.comparingLong(TypeUsage::bytes).reversed())
                .toList();
    }

    /**
     * How much of the sample will ever be released on its own.
     *
     * <p>The bands are coarse on purpose. Nobody needs to know that a key expires in eleven hours;
     * what they need to know is whether the memory comes back today, this week, or never.
     */
    private static List<ExpiryBand> expiry(List<KeySize> measured) {
        Map<String, long[]> bands = new LinkedHashMap<>();
        for (String band : List.of("never", "hour", "day", "week", "longer")) {
            bands.put(band, new long[2]);
        }
        for (KeySize key : measured) {
            long[] counts = bands.get(bandOf(key.ttlMillis()));
            counts[0]++;
            counts[1] += key.bytes();
        }
        return bands.entrySet().stream()
                .map(
                        entry ->
                                new ExpiryBand(
                                        entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                .toList();
    }

    private static String bandOf(long ttlMillis) {
        if (ttlMillis < 0) {
            return "never";
        }
        if (ttlMillis <= Duration.ofHours(1).toMillis()) {
            return "hour";
        }
        if (ttlMillis <= Duration.ofDays(1).toMillis()) {
            return "day";
        }
        return ttlMillis <= Duration.ofDays(7).toMillis() ? "week" : "longer";
    }

    private static List<KeySize> largest(List<KeySize> measured) {
        return measured.stream()
                .sorted(Comparator.comparingLong(KeySize::bytes).reversed())
                .limit(LARGEST)
                .toList();
    }
}
