package io.keydra.monitoring.service;

import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.connections.service.ConnectionService;
import io.keydra.engine.CommandStream;
import io.keydra.engine.EngineSelector;
import io.keydra.engine.ObservedCommand;
import io.keydra.monitoring.dto.CommandFrame;
import io.keydra.monitoring.exception.CommandWatchUnsupportedException;
import io.smallrye.mutiny.Multi;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.concurrent.atomic.AtomicLong;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Watching what a target is being asked to do.
 *
 * <p>A busy server runs more commands per second than a browser can draw, so the stream is bounded
 * rather than buffered: when the reader falls behind, the oldest waiting commands are dropped and
 * the next frame that gets through says how many. A watch that quietly grew a queue would end as
 * the server's memory problem, which is the opposite of what a monitoring tool is for.
 */
@ApplicationScoped
public class CommandWatchService {

    private final ConnectionService connections;
    private final EngineSelector engines;
    private final int bufferSize;

    @Inject
    CommandWatchService(
            ConnectionService connections,
            EngineSelector engines,
            @ConfigProperty(name = "keydra.monitoring.watch-buffer", defaultValue = "512")
                    int bufferSize) {
        this.connections = connections;
        this.engines = engines;
        this.bufferSize = bufferSize;
    }

    /**
     * Streams what the target is doing until the reader goes away.
     *
     * <p>The count of what was missed is carried on the next frame rather than sent as one of its
     * own: a reader who is behind cannot be helped by giving them something else to read.
     */
    public Multi<CommandFrame> watch(Long connectionId) {
        AtomicLong dropped = new AtomicLong();
        return connections
                .load(connectionId)
                .onItem()
                .transformToMulti(
                        profile ->
                                stream(profile)
                                        .observe(profile)
                                        .onOverflow()
                                        .invoke(ignored -> dropped.incrementAndGet())
                                        .buffer(bufferSize)
                                        .map(command -> toFrame(command, dropped)));
    }

    private static CommandFrame toFrame(ObservedCommand command, AtomicLong dropped) {
        return new CommandFrame(
                command.atMicros(),
                command.database(),
                command.client(),
                command.name(),
                command.arguments(),
                dropped.getAndSet(0));
    }

    private CommandStream stream(ConnectionProfile profile) {
        return engines.forProfile(profile)
                .commandStream()
                .orElseThrow(() -> new CommandWatchUnsupportedException(profile.engine.name()));
    }

    /**
     * Whether this target can be watched at all, which the endpoint asks before opening a socket.
     */
    public boolean isSupported(ConnectionProfile profile) {
        return engines.forProfile(profile).commandStream().isPresent();
    }
}
