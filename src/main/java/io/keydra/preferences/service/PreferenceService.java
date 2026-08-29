package io.keydra.preferences.service;

import io.keydra.authz.service.CallerPermissions;
import io.keydra.preferences.dto.PreferenceDtos.Preferences;
import io.keydra.preferences.persistence.PreferenceRepository;
import io.quarkus.hibernate.reactive.panache.common.WithSession;
import io.quarkus.hibernate.reactive.panache.common.WithTransaction;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;

/**
 * Somebody's own preferences, and nobody else's.
 *
 * <p>There is no permission here and that is deliberate, the same decision phase 24 made about
 * sessions: managing your own is part of being signed in rather than something to be granted. There
 * is also no endpoint for reading anybody else's — what a person prefers is a small thing, and a
 * small thing that is theirs.
 *
 * <p>An instance with enforcement off has no account to keep them with, so this answers "nothing,
 * and nowhere to put it". The browser then keeps its own copy, which is what it did before any of
 * this existed.
 */
@ApplicationScoped
public class PreferenceService {

    private final PreferenceRepository repository;
    private final CallerPermissions caller;

    @Inject
    PreferenceService(PreferenceRepository repository, CallerPermissions caller) {
        this.repository = repository;
        this.caller = caller;
    }

    @WithSession
    public Uni<Preferences> mine() {
        return caller.currentUserId()
                .flatMap(
                        userId ->
                                userId == null
                                        ? Uni.createFrom().item(new Preferences(Map.of(), false))
                                        : repository
                                                .allFor(userId)
                                                .map(all -> new Preferences(all, true)));
    }

    /**
     * One named preference of an account that is not the caller's.
     *
     * <p>The exception to the paragraph above, and a narrow one. It answers a single name for a
     * single account, it is reachable from no endpoint on either surface, and the only caller is
     * the letter Keydra writes to somebody — which needs to know what language they read before
     * they have signed in to say so. Reading the whole map for somebody else is still not a thing
     * that can be asked for.
     */
    @WithSession
    public Uni<String> forAccount(Long userId, String name) {
        return userId == null ? Uni.createFrom().nullItem() : repository.valueFor(userId, name);
    }

    /**
     * Sets one, and answers whether it was kept.
     *
     * <p>False rather than a failure when there is nobody to keep it for: an open instance is a
     * working instance, and a switch that threw an error when somebody flicked it would be the
     * interface reporting a fault where there is a design.
     */
    @WithTransaction
    public Uni<Boolean> set(String name, String value) {
        return caller.currentUserId()
                .flatMap(
                        userId ->
                                userId == null
                                        ? Uni.createFrom().item(false)
                                        : repository
                                                .put(userId, name, value)
                                                .replaceWith(Boolean.TRUE));
    }

    /** Forgets one, so whatever the interface defaults to applies again. */
    @WithTransaction
    public Uni<Boolean> forget(String name) {
        return caller.currentUserId()
                .flatMap(
                        userId ->
                                userId == null
                                        ? Uni.createFrom().item(false)
                                        : repository
                                                .remove(userId, name)
                                                .map(removed -> removed > 0));
    }
}
