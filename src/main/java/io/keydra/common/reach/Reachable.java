package io.keydra.common.reach;

import io.smallrye.mutiny.Uni;
import java.util.List;

/**
 * Something outside Keydra that a domain owns and can ask whether it is there.
 *
 * <p>Implemented by every domain that holds rows pointing at somebody else's service — identity
 * providers, backup destinations — and collected by the checker that runs on the instance holding
 * the chores. The direction is phase 43's, for phase 43's reason: a class in {@code cluster} that
 * reached into {@code authz} and {@code backup} to probe their rows would know the internals of
 * both, and would need editing the day a third kind of outbound thing appeared.
 *
 * <p>It lives in {@code common} because everything depends on {@code common} and {@code common}
 * depends on nothing, so this is the only place two domains can meet without either importing the
 * other.
 *
 * <p><b>Only implement this where a check costs the other side nothing.</b> Fetching a discovery
 * document is a GET somebody's server answers a thousand times an hour; writing a small file into a
 * bucket and taking it away again is a round trip nobody sees. Posting to a chat channel is a
 * message a person reads, so alert deliveries keep the button they have and are deliberately not
 * here — a status page that pinged an incident channel every ten minutes would be one nobody is
 * allowed to keep open.
 */
public interface Reachable {

    /**
     * What kind of thing this is, as it is stored and as the page groups it.
     *
     * <p>Stable: it is half of the key a result is written under, so renaming one orphans every
     * answer already recorded.
     */
    String kind();

    /**
     * What one of these is called in a sentence: "backup destination", "identity provider".
     *
     * <p>Distinct from {@link #kind()}, which is a stable key in a table. This one is read by a
     * person in a chat message, and {@code nightly-s3} on its own could be anything.
     */
    String describedAs();

    /** What there is to check, right now, from the database. */
    Uni<List<Subject>> subjects();

    /**
     * Asks one of them whether it is there.
     *
     * <p>Never fails: "it did not answer, and here is what it said" is the answer being asked for.
     * A failure here would stop the walk at the first thing that is down, which is the moment the
     * page is most worth reading.
     */
    Uni<Outcome> check(Long id);

    /**
     * One thing that can be asked.
     *
     * @param id its own id, within this kind
     * @param name what somebody called it, for the line the page shows
     * @param enabled whether it is switched on. Something switched off is not checked and is not
     *     counted against health — it is off because somebody turned it off.
     */
    record Subject(Long id, String name, boolean enabled) {}

    /**
     * What came back.
     *
     * @param ok whether it answered
     * @param detail what it said, or why it did not — a sentence, never a stack trace, and never a
     *     credential or a connection string
     */
    record Outcome(boolean ok, String detail) {

        public static Outcome fine() {
            return new Outcome(true, null);
        }

        public static Outcome not(String detail) {
            return new Outcome(false, detail);
        }
    }
}
