package io.keydra.authz;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;

import io.keydra.authz.service.PasswordHasher;
import org.junit.jupiter.api.Test;

/** Storing a password so that stealing the table does not mean having the passwords. */
class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void acceptsThePasswordItWasGiven() {
        String stored = hasher.hash("correct horse battery staple");

        assertThat(hasher.matches("correct horse battery staple", stored), is(true));
    }

    @Test
    void refusesAnythingElse() {
        String stored = hasher.hash("correct horse battery staple");

        assertThat(hasher.matches("correct horse battery stapl", stored), is(false));
        assertThat(hasher.matches("", stored), is(false));
        assertThat(hasher.matches(null, stored), is(false));
    }

    @Test
    void neverStoresThePassword() {
        String stored = hasher.hash("hunter2");

        // The obvious mistake, and the one worth a test: a hash that contains the thing it
        // was supposed to replace.
        assertThat(stored, not(containsString("hunter2")));
    }

    @Test
    void twoPeopleWithTheSamePasswordHaveDifferentHashes() {
        // A shared password that produced a shared hash would tell an attacker who to guess
        // once for, and let one cracked hash unlock several accounts.
        assertThat(hasher.hash("password"), not(is(hasher.hash("password"))));
    }

    @Test
    void carriesItsOwnParametersSoRaisingThemLaterDoesNotLockAnybodyOut() {
        String stored = hasher.hash("hunter2");

        assertThat(stored, startsWith("argon2id$"));
        // The settings are in the hash, so an old one keeps verifying with the settings it
        // was made with rather than being tested against today's and failing.
        assertThat(stored.split("\\$").length, is(6));
    }

    @Test
    void knowsWhenAStoredHashIsWeakerThanTodaysSettings() {
        String current = hasher.hash("hunter2");
        String older = "argon2id$4096$1$1$c2FsdHNhbHRzYWx0c2E=$aGFzaGhhc2hoYXNoaGFzaGhhc2hoYQ==";

        assertThat(hasher.needsRehash(current), is(false));
        assertThat(hasher.needsRehash(older), is(true));
    }

    @Test
    void treatsAnUnreadableStoredValueAsNoMatchRatherThanAnError() {
        // A row from a scheme this no longer understands is one somebody will have to reset.
        // Saying "no" is the safe half of that; throwing would take the login page with it.
        assertThat(hasher.matches("hunter2", "not-a-hash"), is(false));
        assertThat(hasher.matches("hunter2", "argon2id$broken"), is(false));
        assertThat(hasher.needsRehash("not-a-hash"), is(true));
    }
}
