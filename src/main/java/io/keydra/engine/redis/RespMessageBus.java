package io.keydra.engine.redis;

import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.engine.ChannelMessage;
import io.keydra.engine.MessageBus;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import io.vertx.redis.client.Command;
import io.vertx.redis.client.Redis;
import io.vertx.redis.client.RedisConnection;
import io.vertx.redis.client.Request;
import io.vertx.redis.client.Response;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * RESP publish/subscribe.
 *
 * <p>A subscription gets a connection of its own, taken outside the pool. SUBSCRIBE puts a RESP
 * connection into a mode where it answers nothing else, so a pooled connection used for one would
 * stop serving every other part of the application that shares it — the key browser included.
 *
 * <p>The connection is closed when the stream is cancelled, which is what makes unsubscribing a
 * matter of dropping the subscriber rather than of tracking state on both sides.
 */
@ApplicationScoped
public class RespMessageBus implements MessageBus {

    /** First element of a push frame carrying a message on a named channel. */
    private static final String MESSAGE = "message";

    /** First element of a push frame carrying a message matched by a pattern. */
    private static final String PATTERN_MESSAGE = "pmessage";

    private final RespConnectionPool pool;

    @Inject
    RespMessageBus(RespConnectionPool pool) {
        this.pool = pool;
    }

    @Override
    public Multi<ChannelMessage> subscribe(
            ConnectionProfile profile, List<String> channels, List<String> patterns) {
        if (channels.isEmpty() && patterns.isEmpty()) {
            return Multi.createFrom().empty();
        }

        return Multi.createFrom()
                .emitter(
                        emitter ->
                                // The client may need a tunnel opened first, so it is asked
                                // for asynchronously and the subscription starts once it exists.
                                pool.createUnpooled(profile)
                                        .subscribe()
                                        .with(
                                                client ->
                                                        subscribeWith(
                                                                client, emitter, channels,
                                                                patterns),
                                                emitter::fail));
    }

    /** Wires one client's push frames into the emitter and opens the subscription. */
    private static void subscribeWith(
            Redis client,
            io.smallrye.mutiny.subscription.MultiEmitter<? super ChannelMessage> emitter,
            List<String> channels,
            List<String> patterns) {
        client.connect()
                .onFailure(emitter::fail)
                .onSuccess(
                        connection -> {
                            connection.handler(frame -> emit(emitter, frame));
                            connection.exceptionHandler(emitter::fail);
                            // A dropped connection ends the stream rather
                            // than leaving a subscriber that hears nothing.
                            connection.endHandler(ignored -> emitter.complete());
                            send(connection, Command.SUBSCRIBE, channels);
                            send(connection, Command.PSUBSCRIBE, patterns);
                            emitter.onTermination(
                                    () -> {
                                        connection.close();
                                        client.close();
                                    });
                        });
    }

    private static void send(RedisConnection connection, Command command, List<String> arguments) {
        if (arguments.isEmpty()) {
            return;
        }
        Request request = Request.cmd(command);
        arguments.forEach(request::arg);
        connection.send(request);
    }

    /**
     * Turns one push frame into a message, ignoring the rest.
     *
     * <p>The server also pushes confirmations for each SUBSCRIBE and each unsubscribe. They are
     * part of the protocol rather than something anyone published, so they are not passed on.
     */
    private static void emit(
            io.smallrye.mutiny.subscription.MultiEmitter<? super ChannelMessage> emitter,
            Response frame) {
        if (frame == null || frame.size() < 3) {
            return;
        }
        String kind = frame.get(0).toString();
        if (MESSAGE.equals(kind)) {
            emitter.emit(
                    new ChannelMessage(frame.get(1).toString(), null, frame.get(2).toString()));
        } else if (PATTERN_MESSAGE.equals(kind) && frame.size() >= 4) {
            emitter.emit(
                    new ChannelMessage(
                            frame.get(2).toString(),
                            frame.get(1).toString(),
                            frame.get(3).toString()));
        }
    }

    @Override
    public Uni<Long> publish(ConnectionProfile profile, String channel, String payload) {
        // Publishing is an ordinary command, so it uses the shared client.
        return pool.send(profile, Request.cmd(Command.PUBLISH).arg(channel).arg(payload))
                .map(response -> response == null ? 0L : response.toLong());
    }
}
