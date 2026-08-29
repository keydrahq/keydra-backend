package io.keydra.authz.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.keydra.authz.entity.Permission;
import io.keydra.store.service.KeydraStore;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * What Keydra has already worked out about who somebody is and what they may do.
 *
 * <p>Every request arrives with a cookie and leaves with an answer, and working out that answer
 * meant reading the account, its groups, the groups above those, the grants that reach it and the
 * roles those name — before any of the actual work started, and again on the next request, for an
 * answer that had not changed.
 *
 * <p>Two things keep it honest. The entries expire, so the worst a missed invalidation can do is
 * last a few seconds. And anything that changes an account, a group or a grant clears the lot: not
 * the entries it can prove are affected, but all of them, because working out who a new grant on a
 * parent group reaches is the same computation this cache exists to avoid, and getting it subtly
 * wrong means somebody keeps access they were told they had lost.
 *
 * <p>Phase 9 decided a session carries no copy of its roles — a revocation takes effect on the next
 * request rather than at the next sign-in — and that decision survives this one intact.
 */
@ApplicationScoped
public class AuthzCache {

    private static final Logger LOG = Logger.getLogger(AuthzCache.class);

    /** Everything this cache writes lives under here, so clearing it is one prefix. */
    private static final String PREFIX = "authz:";

    /** Written when the answer is "there is no such account", which is worth not re-asking. */
    private static final String ABSENT = "-";

    /**
     * Who somebody is, in the facts every request needs.
     *
     * @param userId the account's id, which saves the lookup that turns a name into one
     * @param roles the built-in roles their grants add up to, as the coarse gates read them
     * @param owesAFactor whether this installation requires a second factor and this account has
     *     not paired one — in which case the roles above are what it would hold once it has, and
     *     what it holds now is nothing. Cached with the rest because it is worked out from the same
     *     account row, and cleared with the rest by the two things that change it: a factor being
     *     confirmed, and the policy being moved.
     */
    public record Identity(Long userId, Set<String> roles, boolean owesAFactor) {}

    private final KeydraStore store;
    private final ObjectMapper json;
    private final Duration ttl;

    @Inject
    AuthzCache(
            KeydraStore store,
            ObjectMapper json,
            @ConfigProperty(name = "keydra.store.authz-ttl", defaultValue = "30s") Duration ttl) {
        this.store = store;
        this.json = json;
        this.ttl = ttl;
    }

    /** Whether caching is on at all; zero turns it off without removing the code path. */
    private boolean enabled() {
        return !ttl.isZero() && !ttl.isNegative();
    }

    /**
     * The identity behind a username, computed only when it is not already known.
     *
     * @param compute how to work it out, which is what a miss falls back to
     */
    public Uni<Identity> identity(String username, Supplier<Uni<Identity>> compute) {
        if (!enabled()) {
            return compute.get();
        }
        String key = PREFIX + "identity:" + username;
        return store.get(key)
                .flatMap(
                        held -> {
                            if (held.isPresent()) {
                                return Uni.createFrom().item(readIdentity(held.get()));
                            }
                            return compute.get()
                                    .call(fresh -> store.put(key, writeIdentity(fresh), ttl));
                        });
    }

    /**
     * The permissions a user holds over one target, or over Keydra itself when the id is null.
     *
     * <p>Keyed by both, because they are different answers: what somebody may do to a server is not
     * what they may do to the application that manages it.
     */
    public Uni<Set<Permission>> permissions(
            Long userId, Long connectionId, Supplier<Uni<Set<Permission>>> compute) {
        if (!enabled() || userId == null) {
            return compute.get();
        }
        String key =
                PREFIX + "perms:" + userId + ":" + (connectionId == null ? "self" : connectionId);
        return store.get(key)
                .flatMap(
                        held -> {
                            if (held.isPresent()) {
                                return Uni.createFrom().item(readPermissions(held.get()));
                            }
                            return compute.get()
                                    .call(fresh -> store.put(key, writePermissions(fresh), ttl));
                        });
    }

    /**
     * Forgets everything, because something about who may do what has changed.
     *
     * <p>All of it rather than the part that can be proven affected. A grant added to a group two
     * levels up reaches accounts nobody named, and deciding which ones is the walk this cache
     * exists to skip — doing it here to save a few entries would put the same subtle mistake in two
     * places, and the one that matters is somebody keeping access they were told they had lost.
     */
    public Uni<Void> forgetEverything() {
        if (!enabled()) {
            return Uni.createFrom().voidItem();
        }
        LOG.debug("Access changed; forgetting what was worked out");
        return store.forgetUnder(PREFIX);
    }

    // --- Reading and writing what is held ----------------------------------

    private String writeIdentity(Identity identity) {
        if (identity == null) {
            return ABSENT;
        }
        try {
            return json.writeValueAsString(identity);
        } catch (Exception impossible) {
            return ABSENT;
        }
    }

    private Identity readIdentity(String held) {
        if (ABSENT.equals(held)) {
            return null;
        }
        try {
            return json.readValue(held, Identity.class);
        } catch (Exception unreadable) {
            // An entry written by an older version, or a truncated one. Treated as a miss.
            return null;
        }
    }

    private String writePermissions(Set<Permission> permissions) {
        try {
            return json.writeValueAsString(permissions.stream().map(Enum::name).toList());
        } catch (Exception impossible) {
            return "[]";
        }
    }

    private Set<Permission> readPermissions(String held) {
        EnumSet<Permission> permissions = EnumSet.noneOf(Permission.class);
        try {
            List<String> names = json.readValue(held, new TypeRef());
            for (String name : names) {
                // A permission this version does not have is one an older or newer Keydra
                // wrote. Skipped rather than fatal: the entry expires in seconds either way.
                try {
                    permissions.add(Permission.valueOf(name));
                } catch (IllegalArgumentException unknown) {
                    LOG.debugf("Ignoring an unknown permission in the store: %s", name);
                }
            }
        } catch (Exception unreadable) {
            return Set.of();
        }
        return permissions;
    }

    /** Jackson needs the list's element type at runtime, and a record cannot carry it. */
    private static final class TypeRef
            extends com.fasterxml.jackson.core.type.TypeReference<List<String>> {}
}
