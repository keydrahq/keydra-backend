package io.keydra.cluster;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.keydra.cluster.persistence.LeaseRepository;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.vertx.VertxContextSupport;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.function.Supplier;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;

/**
 * The one row two instances have to agree about.
 *
 * <p>Under a role of this test's own rather than the real one, because the instance running the
 * test holds that one and taking it away would stop the work this suite is otherwise checking.
 *
 * <p>What is being tested is a promise about simultaneity that cannot be observed by running two
 * processes and hoping: the statement either refuses the second holder or it does not.
 */
@QuarkusTest
class LeaseTest {

    private static final String ROLE = "test-chores";

    @Inject LeaseRepository leases;

    @Test
    void theSecondInstanceIsToldNo() {
        assertTrue(await(() -> leases.claim(ROLE, "one", 30)));
        assertFalse(
                await(() -> leases.claim(ROLE, "two", 30)),
                "A live lease belongs to whoever took it");
        // And having been told no, it has not quietly moved the expiry either.
        assertTrue(await(() -> leases.claim(ROLE, "one", 30)), "The holder renews its own");
        assertTrue(await(() -> leases.release(ROLE, "one")));
    }

    @Test
    void aLeaseNobodyRenewsGoesToWhoeverAsksNext() {
        assertTrue(await(() -> leases.claim(ROLE + "-short", "leaving", 1)));

        // Nothing releases it: this is the instance that was killed rather than asked, which is
        // the case the whole arrangement exists for.
        Awaitility.await()
                .atMost(Duration.ofSeconds(10))
                .until(() -> await(() -> leases.claim(ROLE + "-short", "arriving", 30)));

        assertTrue(await(() -> leases.release(ROLE + "-short", "arriving")));
    }

    @Test
    void nobodyElseCanGiveItUpOnTheHoldersBehalf() {
        assertTrue(await(() -> leases.claim(ROLE + "-mine", "mine", 30)));
        assertFalse(await(() -> leases.release(ROLE + "-mine", "theirs")));
        assertTrue(await(() -> leases.claim(ROLE + "-mine", "mine", 30)));
        assertTrue(await(() -> leases.release(ROLE + "-mine", "mine")));
    }

    private static <T> T await(Supplier<Uni<T>> work) {
        try {
            return VertxContextSupport.subscribeAndAwait(work);
        } catch (Throwable failure) {
            throw new IllegalStateException("Could not ask about the lease", failure);
        }
    }
}
