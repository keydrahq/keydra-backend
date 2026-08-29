package io.keydra.values.service;

import io.keydra.connections.service.ConnectionService;
import io.keydra.engine.EngineSelector;
import io.keydra.events.dto.NotificationCategory;
import io.keydra.events.service.NotificationHub;
import io.keydra.values.decoder.DecoderChain;
import io.keydra.values.dto.ValueMutation;
import io.keydra.values.dto.ValuePage;
import io.keydra.values.dto.ValueQuery;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;

/** Reading and editing the value behind a key. */
@ApplicationScoped
public class ValueService {

    private final ConnectionService connections;
    private final EngineSelector engines;
    private final DecoderChain decoders;
    private final NotificationHub hub;

    @Inject
    ValueService(
            ConnectionService connections,
            EngineSelector engines,
            DecoderChain decoders,
            NotificationHub hub) {
        this.connections = connections;
        this.engines = engines;
        this.decoders = decoders;
        this.hub = hub;
    }

    /** Decoders a client may ask for by name. */
    public List<String> encodings() {
        return decoders.available();
    }

    public Uni<ValuePage> read(
            Long connectionId, Integer database, ValueQuery query, String encoding) {
        return connections
                .load(connectionId, database)
                .flatMap(
                        profile -> engines.forProfile(profile).readValue(profile, query, encoding));
    }

    public Uni<Long> mutate(Long connectionId, Integer database, ValueMutation mutation) {
        return connections
                .load(connectionId, database)
                .flatMap(profile -> engines.forProfile(profile).mutateValue(profile, mutation))
                .invoke(affected -> announce(connectionId, mutation, affected));
    }

    /** Value edits are broadcast so another viewer of the same key sees the change. */
    private void announce(Long connectionId, ValueMutation mutation, long affected) {
        hub.broadcast(
                NotificationCategory.VALUE_CHANGED,
                connectionId,
                Map.of(
                        "connectionId",
                        connectionId,
                        "key",
                        mutation.key(),
                        "operation",
                        mutation.getClass().getSimpleName(),
                        "affected",
                        affected));
    }
}
