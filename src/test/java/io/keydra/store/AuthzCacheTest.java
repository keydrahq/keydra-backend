package io.keydra.store;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import io.keydra.authz.entity.Permission;
import io.keydra.authz.service.AuthzCache;
import io.keydra.store.service.KeydraStore;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * What Keydra remembers about who may do what, and when it stops remembering.
 *
 * <p>The half that matters is the forgetting. Phase 9 decided a session carries no copy of its
 * roles — a revocation takes effect on the next request rather than at the next sign-in — and a
 * cache is exactly the thing that quietly undoes such a decision. So these tests are mostly about
 * the cache being wrong on purpose: cleared by anything that touches access, and expiring anyway
 * for the change nobody heard about.
 */
@QuarkusTest
class AuthzCacheTest {

    @Inject AuthzCache cache;
    @Inject KeydraStore store;

    @BeforeEach
    void empty() {
        cache.forgetEverything().await().indefinitely();
    }

    @Test
    void worksSomethingOutOnceAndThenRemembersIt() {
        AtomicInteger computed = new AtomicInteger();
        Set<Permission> answer = EnumSet.of(Permission.KEYS_READ);

        for (int i = 0; i < 3; i++) {
            Set<Permission> held =
                    cache.permissions(
                                    7L,
                                    42L,
                                    () -> {
                                        computed.incrementAndGet();
                                        return io.smallrye.mutiny.Uni.createFrom().item(answer);
                                    })
                            .await()
                            .indefinitely();
            assertThat(held, equalTo(answer));
        }

        assertThat(computed.get(), equalTo(1));
    }

    @Test
    void keepsTheAnswerForOneTargetApartFromAnother() {
        // What somebody may do to a server is not what they may do to the application that
        // manages it, and a cache that confused the two would be a privilege escalation.
        cache.permissions(
                        7L,
                        1L,
                        () ->
                                io.smallrye.mutiny.Uni.createFrom()
                                        .item(EnumSet.of(Permission.KEYS_READ)))
                .await()
                .indefinitely();

        Set<Permission> elsewhere =
                cache.permissions(
                                7L,
                                2L,
                                () ->
                                        io.smallrye.mutiny.Uni.createFrom()
                                                .item(EnumSet.of(Permission.KEYS_WRITE)))
                        .await()
                        .indefinitely();

        assertThat(elsewhere, equalTo(EnumSet.of(Permission.KEYS_WRITE)));
    }

    @Test
    void forgetsEverythingRatherThanWhatItCanProveIsAffected() {
        AtomicInteger computed = new AtomicInteger();
        cache.permissions(7L, 42L, () -> counted(computed, EnumSet.of(Permission.KEYS_READ)))
                .await()
                .indefinitely();

        cache.forgetEverything().await().indefinitely();
        cache.permissions(7L, 42L, () -> counted(computed, EnumSet.of(Permission.KEYS_READ)))
                .await()
                .indefinitely();

        assertThat(computed.get(), equalTo(2));
    }

    @Test
    void changingAGrantIsWhatMakesItForget() {
        // The end this is all for: a grant edited through the API leaves nothing cached about
        // access, whichever account or target it happens to name.
        cache.permissions(
                        7L,
                        42L,
                        () -> counted(new AtomicInteger(), EnumSet.noneOf(Permission.class)))
                .await()
                .indefinitely();
        assertThat(held("authz:perms:7:42"), is(true));

        int groupId =
                given().contentType(ContentType.JSON)
                        .body(Map.of("name", "cache-test-group"))
                        .when()
                        .post("/api/v1/authz/groups")
                        .then()
                        .statusCode(201)
                        .extract()
                        .path("id");

        assertThat(held("authz:perms:7:42"), is(false));

        given().when().delete("/api/v1/authz/groups/" + groupId).then().statusCode(204);
    }

    private io.smallrye.mutiny.Uni<Set<Permission>> counted(
            AtomicInteger computed, Set<Permission> answer) {
        computed.incrementAndGet();
        return io.smallrye.mutiny.Uni.createFrom().item(answer);
    }

    private boolean held(String key) {
        return store.get(key).await().indefinitely().isPresent();
    }
}
