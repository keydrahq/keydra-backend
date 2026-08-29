package io.keydra.engine.redis;

import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.values.decoder.DecoderChain;
import io.keydra.values.dto.EncodedValue;
import io.keydra.values.dto.ValuePage;
import io.keydra.values.dto.ValueQuery;
import io.smallrye.mutiny.Uni;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;

/**
 * Reads a page of a value over RESP.
 *
 * <p>Split from {@link RespEngine} because reading values is a different job from browsing keys and
 * checking health, and each type needs its own command and its own way of paging: SCAN cursors for
 * hash, set and sorted-set, index windows for list, and id ranges for stream.
 *
 * <p>COUNT is a hint, not a limit. A collection small enough to be stored as a listpack (Redis 8
 * defaults: 128 list entries, 512 hash/set/zset entries) is returned whole and COUNT is ignored, so
 * a client must page by following the cursor rather than by assuming a page size.
 */
@ApplicationScoped
public class RespValueReader {

    /** Redis' own "iteration finished" cursor. */
    private static final String CURSOR_END = "0";

    private final RespConnectionPool pool;
    private final DecoderChain decoders;

    @Inject
    RespValueReader(RespConnectionPool pool, DecoderChain decoders) {
        this.pool = pool;
        this.decoders = decoders;
    }

    /** Dispatches on the key's own type, which the caller has already resolved. */
    public Uni<ValuePage> read(
            ConnectionProfile profile, String type, ValueQuery query, String encoding) {
        return switch (type) {
            case "string" -> readString(profile, query, encoding);
            case "hash" -> readHash(profile, query, encoding);
            case "list" -> readList(profile, query, encoding);
            case "set" -> readSet(profile, query, encoding);
            case "zset" -> readZSet(profile, query, encoding);
            case "stream" -> readStream(profile, query, encoding);
            default ->
                    Uni.createFrom()
                            .failure(
                                    new IllegalArgumentException(
                                            "Unsupported value type: " + type));
        };
    }

    private EncodedValue decode(Response response, String encoding) {
        return decoders.decode(response == null ? null : response.toBytes(), encoding);
    }

    private Uni<ValuePage> readString(
            ConnectionProfile profile, ValueQuery query, String encoding) {
        return pool.send(profile, Request.cmd(Command.GET).arg(query.key()))
                .map(response -> new ValuePage.StringPage(decode(response, encoding)));
    }

    private Uni<ValuePage> readHash(ConnectionProfile profile, ValueQuery query, String encoding) {
        Request scan =
                Request.cmd(Command.HSCAN)
                        .arg(query.key())
                        .arg(query.cursor())
                        .arg("COUNT")
                        .arg(query.count());

        return pool.send(profile, scan)
                .flatMap(
                        response -> {
                            String next = nextCursor(response);
                            Response flat = response.get(1);
                            List<ValuePage.HashPage.Field> fields =
                                    new ArrayList<>(flat.size() / 2);
                            // HSCAN returns a flat name, value, name, value... sequence.
                            for (int i = 0; i + 1 < flat.size(); i += 2) {
                                fields.add(
                                        new ValuePage.HashPage.Field(
                                                flat.get(i).toString(),
                                                decode(flat.get(i + 1), encoding)));
                            }
                            return total(profile, Command.HLEN, query.key())
                                    .map(size -> new ValuePage.HashPage(fields, next, size));
                        });
    }

    private Uni<ValuePage> readList(ConnectionProfile profile, ValueQuery query, String encoding) {
        // A list pages by index, so the cursor is the next index to read from.
        long start = parseIndex(query.cursor());
        long stop = start + query.count() - 1;

        return pool.send(profile, Request.cmd(Command.LRANGE).arg(query.key()).arg(start).arg(stop))
                .flatMap(
                        response -> {
                            List<ValuePage.ListPage.Element> elements =
                                    new ArrayList<>(response.size());
                            for (int i = 0; i < response.size(); i++) {
                                elements.add(
                                        new ValuePage.ListPage.Element(
                                                start + i, decode(response.get(i), encoding)));
                            }
                            String next =
                                    response.size() < query.count()
                                            ? null
                                            : String.valueOf(stop + 1);
                            return total(profile, Command.LLEN, query.key())
                                    .map(size -> new ValuePage.ListPage(elements, next, size));
                        });
    }

    private Uni<ValuePage> readSet(ConnectionProfile profile, ValueQuery query, String encoding) {
        Request scan =
                Request.cmd(Command.SSCAN)
                        .arg(query.key())
                        .arg(query.cursor())
                        .arg("COUNT")
                        .arg(query.count());

        return pool.send(profile, scan)
                .flatMap(
                        response -> {
                            String next = nextCursor(response);
                            Response members = response.get(1);
                            List<EncodedValue> values = new ArrayList<>(members.size());
                            members.forEach(member -> values.add(decode(member, encoding)));
                            return total(profile, Command.SCARD, query.key())
                                    .map(size -> new ValuePage.SetPage(values, next, size));
                        });
    }

    private Uni<ValuePage> readZSet(ConnectionProfile profile, ValueQuery query, String encoding) {
        Request scan =
                Request.cmd(Command.ZSCAN)
                        .arg(query.key())
                        .arg(query.cursor())
                        .arg("COUNT")
                        .arg(query.count());

        return pool.send(profile, scan)
                .flatMap(
                        response -> {
                            String next = nextCursor(response);
                            Response flat = response.get(1);
                            List<ValuePage.ZSetPage.Member> members =
                                    new ArrayList<>(flat.size() / 2);
                            // ZSCAN returns member, score, member, score...
                            for (int i = 0; i + 1 < flat.size(); i += 2) {
                                members.add(
                                        new ValuePage.ZSetPage.Member(
                                                decode(flat.get(i), encoding),
                                                Double.parseDouble(flat.get(i + 1).toString())));
                            }
                            return total(profile, Command.ZCARD, query.key())
                                    .map(size -> new ValuePage.ZSetPage(members, next, size));
                        });
    }

    private Uni<ValuePage> readStream(
            ConnectionProfile profile, ValueQuery query, String encoding) {
        // Streams page by entry id; "-" is the beginning, and a resumed cursor is exclusive.
        String start = CURSOR_END.equals(query.cursor()) ? "-" : "(" + query.cursor();

        return pool.send(
                        profile,
                        Request.cmd(Command.XRANGE)
                                .arg(query.key())
                                .arg(start)
                                .arg("+")
                                .arg("COUNT")
                                .arg(query.count()))
                .flatMap(
                        response -> {
                            List<ValuePage.StreamPage.Entry> entries =
                                    new ArrayList<>(response.size());
                            for (int i = 0; i < response.size(); i++) {
                                Response entry = response.get(i);
                                Response flat = entry.get(1);
                                List<ValuePage.HashPage.Field> fields =
                                        new ArrayList<>(flat.size() / 2);
                                for (int f = 0; f + 1 < flat.size(); f += 2) {
                                    fields.add(
                                            new ValuePage.HashPage.Field(
                                                    flat.get(f).toString(),
                                                    decode(flat.get(f + 1), encoding)));
                                }
                                entries.add(
                                        new ValuePage.StreamPage.Entry(
                                                entry.get(0).toString(), fields));
                            }
                            String next =
                                    entries.size() < query.count()
                                            ? null
                                            : entries.get(entries.size() - 1).id();
                            return total(profile, Command.XLEN, query.key())
                                    .map(size -> new ValuePage.StreamPage(entries, next, size));
                        });
    }

    /** Null once the scan has come full circle, so a caller knows to stop. */
    private static String nextCursor(Response scanResponse) {
        String cursor = scanResponse.get(0).toString();
        return CURSOR_END.equals(cursor) ? null : cursor;
    }

    private static long parseIndex(String cursor) {
        try {
            return Long.parseLong(cursor);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Element count, which every collection type can report in constant time. */
    private Uni<Long> total(ConnectionProfile profile, Command command, String key) {
        return pool.send(profile, Request.cmd(command).arg(key))
                .map(response -> response == null ? null : response.toLong());
    }
}
