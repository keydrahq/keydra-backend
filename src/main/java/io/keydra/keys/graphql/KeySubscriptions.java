package io.keydra.keys.graphql;

import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.engine.KeyQuery;
import io.keydra.keys.dto.KeyEntry;
import io.keydra.keys.service.KeyService;
import io.keydra.security.Roles;
import io.smallrye.graphql.api.Subscription;
import io.smallrye.mutiny.Multi;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Name;

/**
 * Walking a keyspace, one key at a time.
 *
 * <p>The one place in Keydra where a subscription earns its keep. A scan has no total until it
 * finishes and no page anybody asked for — it is a walk — and every other long list here is a table
 * with rows in it. Streaming those would be a different transport for the same bytes.
 *
 * <p>What it adds over the server-sent events that already do this: the caller says which fields it
 * wants. A key browser showing names and types is sent names and types, rather than being sent the
 * TTL and size of every key in a million-key database because one column somewhere might want them.
 *
 * <p>Permission is asked per target, by the same interceptor the REST endpoint uses, so a
 * subscription cannot be the way somebody reads a keyspace they may not read. It is asked when the
 * subscription is established — which is the only moment there is, and the reason a long-lived
 * stream is closed when the grant behind it is revoked rather than being trusted to have been
 * checked once.
 */
@GraphQLApi
public class KeySubscriptions {

    private final KeyService service;

    @Inject
    KeySubscriptions(KeyService service) {
        this.service = service;
    }

    @Subscription("keys")
    @Description("Walks a target's keyspace with SCAN, one key at a time. KEYS is never used.")
    @RolesAllowed({Roles.VIEWER, Roles.OPERATOR, Roles.ADMIN})
    @RequiresPermission(value = Permission.KEYS_READ, connection = "connectionId")
    public Multi<KeyEntry> keys(
            @Name("connectionId") @Description("The target to walk") Long connectionId,
            @Name("database") @Description("Which database, or the profile's own") Integer database,
            @Name("match") @Description("A glob, or every key") String match,
            @Name("count") @Description("How many keys a SCAN asks for at a time") Integer count,
            @Name("type") @Description("Only keys of this type") String type) {
        return service.scan(
                connectionId,
                database,
                new KeyQuery(match, count == null ? KeyQuery.DEFAULT_COUNT : count, type));
    }
}
