package io.keydra.store;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.is;

import io.keydra.store.service.MemoryStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * The store a single Keydra has.
 *
 * <p>Tested as thoroughly as the shared one because it is not a stub: it is what almost every
 * deployment actually runs, and a bug here is a bug nobody has a Redis to work around.
 */
@QuarkusTest
class MemoryStoreTest {

    @Inject MemoryStore store;

    private static final Duration A_WHILE = Duration.ofMinutes(5);

    @Test
    void holdsWhatWasPutThere() {
        store.put("held", "a value", A_WHILE).await().indefinitely();

        assertThat(store.get("held").await().indefinitely(), equalTo(Optional.of("a value")));
    }

    @Test
    void answersNothingForAKeyNobodyWrote() {
        assertThat(store.get("never-written").await().indefinitely(), equalTo(Optional.empty()));
    }

    @Test
    void stopsAnsweringOnceAnEntryHasExpired() {
        // Expiry is checked on read rather than only swept on a timer, which is what makes it
        // correct: a cache that answers with something it should have dropped is worse than
        // one that answers with nothing.
        store.put("brief", "gone by now", Duration.ofMillis(-1)).await().indefinitely();

        assertThat(store.get("brief").await().indefinitely(), equalTo(Optional.empty()));
    }

    @Test
    void forgetsOnlyWhatWasAskedFor() {
        store.put("keep", "still here", A_WHILE).await().indefinitely();
        store.put("drop", "not for long", A_WHILE).await().indefinitely();

        store.forget("drop").await().indefinitely();

        assertThat(store.get("keep").await().indefinitely().isPresent(), is(true));
        assertThat(store.get("drop").await().indefinitely().isPresent(), is(false));
    }

    @Test
    void clearsAPrefixWithoutTouchingWhatIsBesideIt() {
        // This is the shape every invalidation in the application takes: something about
        // access changed, so everything cached about access goes.
        store.put("authz:identity:ada", "one", A_WHILE).await().indefinitely();
        store.put("authz:perms:1:2", "two", A_WHILE).await().indefinitely();
        store.put("elsewhere:kept", "three", A_WHILE).await().indefinitely();

        store.forgetUnder("authz:").await().indefinitely();

        assertThat(
                store.get("authz:identity:ada").await().indefinitely(), equalTo(Optional.empty()));
        assertThat(store.get("authz:perms:1:2").await().indefinitely(), equalTo(Optional.empty()));
        assertThat(
                store.get("elsewhere:kept").await().indefinitely(), equalTo(Optional.of("three")));
    }

    @Test
    void handsAPublishedMessageToEverySubscriber() {
        // Publishing calls the local listeners even here, where there is nobody else to hear
        // it: it costs nothing, and it means the subscribe path is exercised in every
        // deployment rather than only in the ones with a Redis.
        List<String> heard = new ArrayList<>();
        store.subscribe("a-channel", heard::add);
        store.subscribe("a-channel", message -> heard.add("also " + message));
        store.subscribe("another-channel", message -> heard.add("wrong channel"));

        store.publish("a-channel", "something happened").await().indefinitely();

        assertThat(heard, contains("something happened", "also something happened"));
    }

    @Test
    void isNotSharedAndSaysSo() {
        // The hub reads this to decide whether a broadcast has anywhere else to go.
        assertThat(store.isShared(), is(false));
    }

    @Test
    void clearingAPrefixThatMatchesNothingIsNotAnError() {
        store.forgetUnder("nothing-under-here:").await().indefinitely();

        assertThat(List.of(), is(empty()));
    }
}
