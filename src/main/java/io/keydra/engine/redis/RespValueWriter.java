package io.keydra.engine.redis;

import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.values.dto.ValueMutation;
import io.smallrye.mutiny.Uni;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.UUID;

/**
 * Applies a value change over RESP.
 *
 * <p>Each mutation maps to exactly one command. The switch is exhaustive over the sealed {@link
 * ValueMutation}, so adding an operation to the SPI fails to compile here until this engine
 * supports it — which is the point of sealing it.
 */
@ApplicationScoped
public class RespValueWriter {

    private final RespConnectionPool pool;

    @Inject
    RespValueWriter(RespConnectionPool pool) {
        this.pool = pool;
    }

    /**
     * A value nothing in a list can already hold, written at an index so that the element there can
     * then be removed by value.
     *
     * <p>Random rather than fixed: two people deleting from the same list at the same moment would
     * otherwise write the same marker, and the second LREM would take both.
     */
    private static String tombstone() {
        return "__keydra_removed__" + UUID.randomUUID();
    }

    public Uni<Long> write(ConnectionProfile profile, ValueMutation mutation) {
        if (mutation instanceof ValueMutation.RemoveListElementAt at) {
            return removeAt(profile, at);
        }
        return pool.send(profile, toRequest(mutation)).map(RespValueWriter::changed);
    }

    /**
     * Removes the element at an index, which Redis has no single command for.
     *
     * <p>Sent as two commands rather than a pipeline: the second is only correct if the first
     * succeeded, and a pipeline reports the pair as one outcome. An LSET against an index that is
     * no longer there fails, which is the right answer — the element somebody clicked is gone.
     */
    private Uni<Long> removeAt(ConnectionProfile profile, ValueMutation.RemoveListElementAt at) {
        String marker = tombstone();
        return pool.send(
                        profile,
                        Request.cmd(Command.LSET).arg(at.key()).arg(at.index()).arg(marker))
                .flatMap(
                        ignored ->
                                pool.send(
                                        profile,
                                        Request.cmd(Command.LREM).arg(at.key()).arg(1).arg(marker)))
                .map(RespValueWriter::changed);
    }

    private static Request toRequest(ValueMutation mutation) {
        return switch (mutation) {
            case ValueMutation.SetString m -> Request.cmd(Command.SET).arg(m.key()).arg(m.value());
            case ValueMutation.SetHashField m ->
                    Request.cmd(Command.HSET).arg(m.key()).arg(m.field()).arg(m.value());
            case ValueMutation.DeleteHashField m ->
                    Request.cmd(Command.HDEL).arg(m.key()).arg(m.field());
            case ValueMutation.SetListElement m ->
                    Request.cmd(Command.LSET).arg(m.key()).arg(m.index()).arg(m.value());
            case ValueMutation.PushListElement m ->
                    Request.cmd(m.toHead() ? Command.LPUSH : Command.RPUSH)
                            .arg(m.key())
                            .arg(m.value());
            case ValueMutation.RemoveListElement m ->
                    Request.cmd(Command.LREM).arg(m.key()).arg(m.count()).arg(m.value());
            // Handled above, because it is two commands rather than one.
            case ValueMutation.RemoveListElementAt ignored ->
                    throw new IllegalStateException("removeListElementAt is sent in two steps");
            case ValueMutation.AddSetMember m ->
                    Request.cmd(Command.SADD).arg(m.key()).arg(m.member());
            case ValueMutation.RemoveSetMember m ->
                    Request.cmd(Command.SREM).arg(m.key()).arg(m.member());
            case ValueMutation.AddScoredMember m ->
                    Request.cmd(Command.ZADD).arg(m.key()).arg(m.score()).arg(m.member());
            case ValueMutation.RemoveScoredMember m ->
                    Request.cmd(Command.ZREM).arg(m.key()).arg(m.member());
            case ValueMutation.AddStreamEntry m -> streamAdd(m);
            case ValueMutation.DeleteStreamEntry m ->
                    Request.cmd(Command.XDEL).arg(m.key()).arg(m.id());
        };
    }

    private static Request streamAdd(ValueMutation.AddStreamEntry entry) {
        // A null id means "*", which lets the server assign the next one.
        Request request =
                Request.cmd(Command.XADD)
                        .arg(entry.key())
                        .arg(entry.id() == null || entry.id().isBlank() ? "*" : entry.id());
        for (Map.Entry<String, String> field : entry.fields().entrySet()) {
            request.arg(field.getKey()).arg(field.getValue());
        }
        return request;
    }

    /**
     * Normalises what a command reports.
     *
     * <p>Some answer a count, SET answers OK, and XADD answers the new entry's id. The caller only
     * wants to know whether anything changed.
     */
    private static long changed(Response response) {
        if (response == null) {
            return 0;
        }
        return switch (response.type()) {
            case NUMBER -> response.toLong();
            default -> 1;
        };
    }
}
