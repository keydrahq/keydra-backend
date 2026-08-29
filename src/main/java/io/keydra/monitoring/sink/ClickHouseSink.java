package io.keydra.monitoring.sink;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.keydra.engine.MetricsSample;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Uni;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import org.apache.camel.Exchange;
import org.apache.camel.ProducerTemplate;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Readings that outlive the process, in ClickHouse.
 *
 * <p>Chosen for the shape of the data rather than for fashion: a wide, dull, append-only stream of
 * numbers per target every few seconds, read back as ranges. A column store compresses that by an
 * order of magnitude and answers "the last month for this target" by scanning two columns, without
 * an index per question anybody might ask. PostgreSQL is already here and would do it — while
 * carrying seventeen thousand rows a day per target that nothing else in it has any use for.
 *
 * <p>Off unless configured. Another service in a deployment is a real cost, and an instance that
 * does not want one is not told it needs one: with this disabled the ring buffer is the only sink
 * and Keydra behaves exactly as it did before there was a second.
 *
 * <p>Over ClickHouse's HTTP interface, through the producer the backups and the alerts already go
 * through. That is not laziness about a driver: the JDBC one is blocking and this application is
 * not, and an HTTP round trip on a worker thread is the same shape as everything else here that
 * talks to something outside.
 */
@ApplicationScoped
public class ClickHouseSink implements MetricsSink {

    private static final Logger LOG = Logger.getLogger(ClickHouseSink.class);

    /** What an aggregate is called in an answer, so it never shares a name with its column. */
    private static final String VALUE_PREFIX = "v_";

    /**
     * The columns, in the order the insert writes them.
     *
     * <p>Named once so the schema, the insert and the range query cannot drift apart — three places
     * that must agree about every field, which is exactly the kind of agreement that stops being
     * true the first time somebody adds a metric.
     */
    private static final List<Column> COLUMNS =
            List.of(
                    Column.gauge("memory_used_bytes", MetricsSample::memoryUsedBytes),
                    Column.gauge("memory_peak_bytes", MetricsSample::memoryPeakBytes),
                    Column.gauge("memory_max_bytes", MetricsSample::memoryMaxBytes),
                    Column.gauge("connected_clients", MetricsSample::connectedClients),
                    Column.gauge("ops_per_second", MetricsSample::opsPerSecond),
                    Column.counter("total_commands", MetricsSample::totalCommands),
                    Column.counter("keyspace_hits", MetricsSample::keyspaceHits),
                    Column.counter("keyspace_misses", MetricsSample::keyspaceMisses),
                    Column.gauge("key_count", MetricsSample::keyCount),
                    Column.counter("uptime_seconds", MetricsSample::uptimeSeconds),
                    Column.counter("evicted_keys", MetricsSample::evictedKeys),
                    Column.counter("expired_keys", MetricsSample::expiredKeys));

    /**
     * One column, and how a bucket of readings is summarised into it.
     *
     * <p>A gauge is averaged and a counter is taken at its highest, which is the difference between
     * them: averaging "commands processed since start" over a minute produces a number that was
     * never true, while the largest reading in the minute is the reading at the end of it.
     */
    private record Column(
            String name, java.util.function.Function<MetricsSample, Long> read, String bucket) {

        static Column gauge(String name, java.util.function.Function<MetricsSample, Long> read) {
            return new Column(name, read, "avg");
        }

        static Column counter(String name, java.util.function.Function<MetricsSample, Long> read) {
            return new Column(name, read, "max");
        }
    }

    private final ProducerTemplate producer;
    private final ObjectMapper json;
    private final Vertx vertx;

    private final boolean enabled;
    private final String url;
    private final String database;
    private final String table;
    private final String username;
    private final String password;
    private final int ttlDays;
    private final int batchSize;
    private final Duration flushEvery;

    /** Readings taken but not yet sent. Bounded, because a store that is down must not grow. */
    private final Queue<MetricsRow> pending = new ConcurrentLinkedQueue<>();

    private volatile boolean ready;
    private volatile long flushTimer = -1;

    private record MetricsRow(Long connectionId, MetricsSample sample) {}

    @Inject
    ClickHouseSink(
            ProducerTemplate producer,
            ObjectMapper json,
            Vertx vertx,
            @ConfigProperty(name = "keydra.metrics.clickhouse.enabled", defaultValue = "false")
                    boolean enabled,
            @ConfigProperty(
                            name = "keydra.metrics.clickhouse.url",
                            defaultValue = "http://localhost:8123")
                    String url,
            @ConfigProperty(name = "keydra.metrics.clickhouse.database", defaultValue = "keydra")
                    String database,
            @ConfigProperty(
                            name = "keydra.metrics.clickhouse.table",
                            defaultValue = "metrics_sample")
                    String table,
            @ConfigProperty(name = "keydra.metrics.clickhouse.username", defaultValue = "default")
                    String username,
            // Optional rather than an empty default: an empty string is not a value as far
            // as configuration is concerned, and a ClickHouse with no password is ordinary.
            @ConfigProperty(name = "keydra.metrics.clickhouse.password")
                    java.util.Optional<String> password,
            @ConfigProperty(name = "keydra.metrics.clickhouse.retention-days", defaultValue = "30")
                    int ttlDays,
            @ConfigProperty(name = "keydra.metrics.clickhouse.batch-size", defaultValue = "200")
                    int batchSize,
            @ConfigProperty(name = "keydra.metrics.clickhouse.flush-every", defaultValue = "10s")
                    Duration flushEvery) {
        this.producer = producer;
        this.json = json;
        this.vertx = vertx;
        this.enabled = enabled;
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.database = database;
        this.table = table;
        this.username = username;
        this.password = password.orElse("");
        this.ttlDays = ttlDays;
        this.batchSize = Math.max(1, batchSize);
        this.flushEvery = flushEvery;
    }

    /**
     * Makes sure the table is there, and starts the timer that empties the buffer.
     *
     * <p>A failure here disables the sink rather than the application: an instance that cannot
     * reach its metrics store should still manage the servers it was opened for, and the ring
     * buffer is still the sink everything else reads.
     */
    void onStart(@Observes StartupEvent ignored) {
        if (!enabled) {
            return;
        }
        try {
            execute("CREATE DATABASE IF NOT EXISTS " + database);
            execute(schema());
            ready = true;
            flushTimer = vertx.setPeriodic(flushEvery.toMillis(), timer -> flushQuietly());
            LOG.infof("Writing readings to ClickHouse at %s, kept %d days", url, ttlDays);
        } catch (RuntimeException unreachable) {
            LOG.errorf(
                    unreachable,
                    "ClickHouse is configured at %s but could not be prepared; readings stay in"
                            + " memory only",
                    url);
        }
    }

    void onStop(@Observes ShutdownEvent ignored) {
        if (flushTimer >= 0) {
            vertx.cancelTimer(flushTimer);
        }
        // One last attempt, so a graceful stop does not throw away the last ten seconds.
        flushQuietly();
    }

    @Override
    public void write(Long connectionId, MetricsSample sample) {
        if (!ready) {
            return;
        }
        pending.add(new MetricsRow(connectionId, sample));
        if (pending.size() >= batchSize) {
            flushQuietly();
        }
    }

    @Override
    public boolean isDurable() {
        return ready;
    }

    @Override
    public Uni<List<MetricsSample>> between(
            Long connectionId, Instant from, Instant to, int points) {
        if (!ready) {
            return Uni.createFrom().item(List.of());
        }
        long seconds = Math.max(1, Duration.between(from, to).toSeconds());
        // A bucket wide enough that the answer is about the size of the chart. Never finer than
        // the sampling interval, because a bucket that holds no readings is a gap somebody would
        // read as an outage.
        long step = Math.max(5, seconds / Math.max(1, points));
        String query = rangeQuery(connectionId, from, to, step);
        return offEventLoop(() -> parse(execute(query)));
    }

    // --- Talking to it -----------------------------------------------------

    private String schema() {
        StringBuilder ddl =
                new StringBuilder("CREATE TABLE IF NOT EXISTS ")
                        .append(database)
                        .append('.')
                        .append(table)
                        .append(" (connection_id UInt64, at DateTime64(3)");
        for (Column column : COLUMNS) {
            ddl.append(", ").append(column.name()).append(" Nullable(Int64)");
        }
        // Ordered by target and time because that is the only question anybody asks of it, and
        // the TTL is ClickHouse's own rather than a retention policy Keydra invents and forgets
        // to run.
        ddl.append(") ENGINE = MergeTree ORDER BY (connection_id, at)")
                .append(" TTL toDateTime(at) + INTERVAL ")
                .append(ttlDays)
                .append(" DAY");
        return ddl.toString();
    }

    /**
     * A window, in buckets, as milliseconds since the epoch.
     *
     * <p>Times go both ways as epoch milliseconds rather than as datetime literals. A server's own
     * timezone is a setting, and a query that renders "2026-08-20 21:06:40" is a query whose answer
     * depends on it; an integer has no timezone to be wrong about.
     *
     * <p>Each aggregate is aliased away from the column it reads. An alias with the column's own
     * name is a name that refers to itself, which is a class of query that works until the day two
     * of them appear in one select.
     */
    private String rangeQuery(Long connectionId, Instant from, Instant to, long step) {
        long stepMillis = step * 1000;
        StringBuilder select =
                new StringBuilder("SELECT intDiv(toUnixTimestamp64Milli(at), ")
                        .append(stepMillis)
                        .append(") * ")
                        .append(stepMillis)
                        .append(" AS bucket");
        for (Column column : COLUMNS) {
            select.append(", ")
                    .append(column.bucket())
                    .append('(')
                    .append(column.name())
                    .append(") AS ")
                    .append(VALUE_PREFIX)
                    .append(column.name());
        }
        return select.append(" FROM ")
                .append(database)
                .append('.')
                .append(table)
                .append(" WHERE connection_id = ")
                .append(connectionId)
                .append(" AND at BETWEEN fromUnixTimestamp64Milli(toInt64(")
                .append(from.toEpochMilli())
                .append(")) AND fromUnixTimestamp64Milli(toInt64(")
                .append(to.toEpochMilli())
                .append(")) GROUP BY bucket ORDER BY bucket FORMAT JSON")
                .toString();
    }

    /** Sends one statement and answers what came back, which is empty for a write. */
    private String execute(String statement) {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put(Exchange.HTTP_METHOD, "POST");
        headers.put(Exchange.CONTENT_TYPE, "text/plain; charset=utf-8");
        headers.put("X-ClickHouse-User", username);
        if (!password.isEmpty()) {
            headers.put("X-ClickHouse-Key", password);
        }
        // Asked for as a String rather than converted afterwards: Camel hands back a stream,
        // and calling toString on one produces the name of the class holding the answer
        // instead of the answer — which parses as neither JSON nor an error.
        String answer = producer.requestBodyAndHeaders(url, statement, headers, String.class);
        return answer == null ? "" : answer;
    }

    private void flushQuietly() {
        if (pending.isEmpty()) {
            return;
        }
        List<MetricsRow> batch = new ArrayList<>();
        MetricsRow row;
        while (batch.size() < batchSize * 5 && (row = pending.poll()) != null) {
            batch.add(row);
        }
        if (batch.isEmpty()) {
            return;
        }
        offEventLoop(
                        () -> {
                            execute(insert(batch));
                            return batch.size();
                        })
                .subscribe()
                .with(
                        written -> LOG.debugf("Wrote %d readings to ClickHouse", written),
                        // Dropped rather than retried: a store that is not answering must not
                        // grow a queue in an application whose job is watching other people's
                        // memory. What is lost is a few seconds of a chart.
                        failure ->
                                LOG.warnf(
                                        failure,
                                        "Could not write %d readings to ClickHouse; they are"
                                                + " dropped",
                                        batch.size()));
    }

    private String insert(List<MetricsRow> batch) {
        StringBuilder body =
                new StringBuilder("INSERT INTO ")
                        .append(database)
                        .append('.')
                        .append(table)
                        .append(" FORMAT JSONEachRow\n");
        for (MetricsRow row : batch) {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("connection_id", row.connectionId());
            // Milliseconds since the epoch: ClickHouse reads an integer into DateTime64(3)
            // as its own ticks, and a fractional second is not something its JSON reader
            // accepts.
            fields.put("at", row.sample().at().toEpochMilli());
            for (Column column : COLUMNS) {
                fields.put(column.name(), column.read().apply(row.sample()));
            }
            try {
                body.append(json.writeValueAsString(fields)).append('\n');
            } catch (Exception impossible) {
                // Numbers and a timestamp; there is nothing here that cannot be written.
                LOG.debug("Could not write a reading as JSON", impossible);
            }
        }
        return body.toString();
    }

    private List<MetricsSample> parse(String answer) {
        if (answer == null || answer.isBlank()) {
            return List.of();
        }
        try {
            JsonNode rows = json.readTree(answer).path("data");
            List<MetricsSample> samples = new ArrayList<>();
            for (JsonNode row : rows) {
                samples.add(toSample(row));
            }
            return samples;
        } catch (Exception unreadable) {
            LOG.warnf(unreadable, "Could not read what ClickHouse answered");
            return List.of();
        }
    }

    private MetricsSample toSample(JsonNode row) {
        Map<String, Long> values = new LinkedHashMap<>();
        for (Column column : COLUMNS) {
            values.put(column.name(), number(row.get(VALUE_PREFIX + column.name())));
        }
        Long bucket = number(row.get("bucket"));
        return new MetricsSample(
                Instant.ofEpochMilli(bucket == null ? 0 : bucket),
                values.get("memory_used_bytes"),
                values.get("memory_peak_bytes"),
                values.get("memory_max_bytes"),
                values.get("connected_clients"),
                values.get("ops_per_second"),
                values.get("total_commands"),
                values.get("keyspace_hits"),
                values.get("keyspace_misses"),
                values.get("key_count"),
                values.get("uptime_seconds"),
                values.get("evicted_keys"),
                values.get("expired_keys"));
    }

    /**
     * One number out of an answer, whether it arrived as a number or as a string.
     *
     * <p>ClickHouse writes 64-bit integers as strings in some of its JSON output, because a JSON
     * number cannot hold all of them without losing precision. Both forms mean the same here.
     */
    private static Long number(JsonNode value) {
        if (value == null || value.isNull()) {
            return null;
        }
        if (value.isTextual()) {
            try {
                return Math.round(Double.parseDouble(value.asText()));
            } catch (NumberFormatException notANumber) {
                return null;
            }
        }
        return Math.round(value.asDouble());
    }

    private <T> Uni<T> offEventLoop(Supplier<T> work) {
        return Uni.createFrom()
                .completionStage(() -> vertx.executeBlocking(work::get, false).toCompletionStage());
    }
}
