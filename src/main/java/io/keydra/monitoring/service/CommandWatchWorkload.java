package io.keydra.monitoring.service;

import io.keydra.common.workload.Workload;
import io.keydra.monitoring.ws.CommandWatchSocket;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * How many command watches this instance is holding open.
 *
 * <p>A stream rather than a socket, in the roster's terms: it is a connection held against a target
 * because somebody is looking at it, and it stays open when a request ends. That is the distinction
 * worth drawing — "this instance has visitors" and "this instance has visitors who are all watching
 * something" are different facts about how expensive it is to restart.
 */
@ApplicationScoped
public class CommandWatchWorkload implements Workload {

    private final OpenConnections connections;

    @Inject
    CommandWatchWorkload(OpenConnections connections) {
        this.connections = connections;
    }

    @Override
    public Snapshot snapshot() {
        int watching = 0;
        for (WebSocketConnection connection : connections) {
            if (CommandWatchSocket.ENDPOINT_ID.equals(connection.endpointId())) {
                watching++;
            }
        }
        return Snapshot.ofStreams(watching);
    }
}
