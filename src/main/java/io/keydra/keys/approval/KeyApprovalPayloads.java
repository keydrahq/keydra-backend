package io.keydra.keys.approval;

import io.keydra.keys.dto.ImportKeysRequest;
import io.keydra.keys.dto.MigrateKeysRequest;
import io.keydra.keys.dto.PurgeKeysRequest;
import java.util.List;

/**
 * What each kind of key operation needs in order to happen later.
 *
 * <p>Not in {@code keys.dto}, because none of these crosses the API: they are what is written into
 * a pending request and read back by the handler that carries it out, and nothing else ever sees
 * one. Records of the request that was made, so what runs is what was asked for down to the field.
 *
 * <p>The database travels with the ones that have one. A purge asked for on database 3 and carried
 * out on database 0 would be a different operation wearing the same description, which is precisely
 * the class of mistake this whole phase is about.
 */
public final class KeyApprovalPayloads {

    private KeyApprovalPayloads() {}

    public record PurgeKeysPayload(Integer database, PurgeKeysRequest request) {}

    public record DeleteKeysPayload(Integer database, List<String> keys) {}

    public record ImportKeysPayload(ImportKeysRequest request) {}

    public record MigrateKeysPayload(MigrateKeysRequest request) {}
}
