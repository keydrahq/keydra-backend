package io.keydra.events.service;

import io.keydra.common.workload.Workload;
import io.keydra.events.ws.Notifications;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocketConnection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * How many browsers this instance is talking to.
 *
 * <p>The one number a load balancer's decisions show up in, and the one worth reading across the
 * fleet rather than alone: twelve on one instance and none on the other is not a busy instance, it
 * is a balancer sending everything one way.
 *
 * <p>Filtered to the notification endpoint rather than counting every socket, because the others
 * are a different thing — a command watch is somebody looking at one target, and counting it here
 * would make a page with one visitor look like a page with two.
 */
@ApplicationScoped
public class SocketWorkload implements Workload {

    private final OpenConnections connections;

    @Inject
    SocketWorkload(OpenConnections connections) {
        this.connections = connections;
    }

    @Override
    public Snapshot snapshot() {
        int open = 0;
        for (WebSocketConnection connection : connections) {
            if (Notifications.ENDPOINT_ID.equals(connection.endpointId())) {
                open++;
            }
        }
        return Snapshot.ofSockets(open);
    }
}
