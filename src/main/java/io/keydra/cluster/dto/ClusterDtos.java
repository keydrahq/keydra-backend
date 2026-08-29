package io.keydra.cluster.dto;

import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** What Keydra says about itself: who is running, and what they are all talking to. */
public final class ClusterDtos {

    private ClusterDtos() {}

    /**
     * One running Keydra.
     *
     * @param id what it calls itself
     * @param version the build it is running, so a rolling upgrade reads as one
     * @param commit the commit that build came from, which a version alone does not pin down
     * @param startedAt when it announced itself for the first time
     * @param lastSeenAt when it last said it was here
     * @param leader whether it is the one doing the work that must happen once
     * @param self whether it is the one that answered this request — which is not the same as the
     *     leader, and is worth marking because everything else on the page came from it
     * @param published how many envelopes it has put on the notification bus, cumulative
     * @param received how many it has taken off it, which is only ever what another instance put
     *     there — the two numbers together are the only evidence that instances are talking, and
     *     they are cumulative so that whoever reads them twice gets a rate and nothing has to keep
     *     a second clock
     * @param commands how many it has sent to the servers it watches, cumulative — the busier of
     *     the two by a long way, since the bus carries a handful of notifications and this carries
     *     every scan, every INFO and every sample
     * @param sockets how many browsers this instance is talking to, as of its last beat. The number
     *     a load balancer's decisions show up in, and one to read across the fleet rather than
     *     alone
     * @param streams connections it holds open against a target because somebody is looking at them
     *     — subscriptions and command watches
     * @param jobs long work under way on it: a keyspace being walked, a tunnel being held
     * @param watching which targets it holds clients for, by profile id. Ids rather than a count,
     *     because a target three instances are watching is three pools against one server. Not a
     *     live reading: it is what the instance reported on its last beat, which is the same
     *     freshness as everything else on the row
     * @param draining whether somebody has asked it to stop taking new work. The only field here
     *     the instance does not say about itself, and the only one that is true the moment it is
     *     written rather than on the next beat — what follows from it, the readiness going down and
     *     the chores moving, happens when the instance next reads the row
     */
    @Schema(name = "InstanceSummary", description = "One running Keydra")
    public record InstanceSummary(
            String id,
            String version,
            String commit,
            Instant startedAt,
            Instant lastSeenAt,
            boolean leader,
            boolean self,
            long published,
            long received,
            long commands,
            int sockets,
            int streams,
            int jobs,
            List<Long> watching,
            boolean draining,
            @Schema(
                            description =
                                    "Whether it is still answering. False for a row that has aged"
                                            + " without being removed, which is an instance that"
                                            + " stopped without shutting down — one that stopped"
                                            + " cleanly is not here at all")
                    boolean present) {}

    /**
     * Something Keydra itself depends on.
     *
     * <p>Not a target. These are the pieces Keydra needs to be Keydra, and a page that lists a
     * fleet of servers without saying whether the thing watching them can reach its own database is
     * a page that will be confidently wrong one morning.
     *
     * @param id a stable, language-neutral name for the thing — {@code database}, {@code
     *     ssh-tunnels}. What the interface matches an icon and a translated label on; before this
     *     existed it matched on the English display name, which made the picture untranslatable and
     *     tied two files together through a sentence
     * @param name what it is, in English. Kept so a script reading the API gets something readable;
     *     the interface prefers its own translation of {@code id}
     * @param kind what sort of thing, so the page can say "PostgreSQL" rather than guess
     * @param configured false for something this deployment has chosen not to have, which is not a
     *     fault and must not be drawn as one
     * @param reachable whether it answered just now
     * @param count how many there are of this kind — one for the database, however many identity
     *     providers or targets somebody has configured. A kind with none is still listed, because
     *     "no backup destination" is a thing worth seeing rather than a row worth hiding
     * @param healthy how many of those are all right, which is only a different number from {@code
     *     count} where there is more than one of something
     * @param detail what it said when it did not answer — an exception's own words, which are
     *     diagnostic rather than prose and are shown as they are
     * @param note a stable key for the standing sentence that explains a state nobody has
     *     configured — {@code mail-off}, {@code shared-store-local}. A key rather than the
     *     sentence, because the sentence is interface text
     * @param reached what came of asking the things in this group whether they answer, or null for
     *     a group nothing asks — the database and the store are reached by every request, and a
     *     chat channel is not asked because asking one sends somebody a message
     */
    @Schema(name = "DependencyState", description = "Something Keydra itself depends on")
    public record DependencyState(
            String id,
            String name,
            String kind,
            boolean configured,
            boolean reachable,
            int count,
            int healthy,
            String detail,
            String note,
            Reached reached) {

        /**
         * What came of asking, and when.
         *
         * <p>Its own record rather than three nullable fields, because the three are one fact: they
         * are all absent together for a group nothing asks, and all present together for one that
         * is asked.
         *
         * @param at when the oldest of these answers was recorded, which is how stale the reading
         *     is rather than how fresh part of it is
         * @param asked how many were asked. Fewer than are configured: something switched off is
         *     not asked, and neither is a provider that names no issuer
         * @param answering how many of those answered
         */
        @Schema(name = "Reached", description = "What came of asking, and when")
        public record Reached(Instant at, int asked, int answering) {}

        /** A single thing that is either there or not: the database, the store, the mail server. */
        public static DependencyState one(
                String id,
                String name,
                String kind,
                boolean configured,
                boolean reachable,
                String detail) {
            return new DependencyState(
                    id,
                    name,
                    kind,
                    configured,
                    reachable,
                    configured ? 1 : 0,
                    reachable ? 1 : 0,
                    detail,
                    null,
                    null);
        }

        /**
         * The same, carrying the standing note that explains a state somebody has not configured.
         *
         * <p>A key rather than the sentence, for the reason the audit log's actions are keys: the
         * sentence is interface text and belongs where the rest of it is. What is said here has to
         * be said in the language the page was asked for, and this end does not know what that is.
         */
        public DependencyState withNote(String key) {
            return new DependencyState(
                    id, name, kind, configured, reachable, count, healthy, detail, key, reached);
        }

        /**
         * However many of something somebody has set up.
         *
         * <p>Configured means there is at least one. Reachable means none of them is in trouble —
         * which is the right roll-up for a group: one identity provider down is a page that should
         * not be green, and saying "3 of 4" beside it is what turns that into something actionable.
         *
         * <p>No {@code detail}: that field is an exception's own words, and a group is counted
         * rather than asked. What its row says is composed from the numbers, where the language is
         * known.
         */
        public static DependencyState many(
                String id, String name, String kind, int count, int healthy) {
            return new DependencyState(
                    id, name, kind, count > 0, count == healthy, count, healthy, null, null, null);
        }

        /**
         * However many of something, with what came of asking them.
         *
         * <p>Reachability narrows health rather than replacing it. Health has always meant "how
         * many are switched on", and something that is off is off because somebody turned it off; a
         * thing that is on and did not answer is a different fault and this is where it shows.
         *
         * <p>A group nothing has asked yet is not made red by that. It has not been asked, which
         * the detail line says — inventing a verdict from the absence of one would be the page
         * making up the one fact it exists to report.
         */
        public static DependencyState many(
                String id, String name, String kind, int count, int healthy, Reached reached) {
            boolean answering = reached == null || reached.answering() == reached.asked();
            return new DependencyState(
                    id,
                    name,
                    kind,
                    count > 0,
                    count == healthy && answering,
                    count,
                    healthy,
                    null,
                    null,
                    reached);
        }
    }

    /**
     * One time something outside Keydra started or stopped answering.
     *
     * @param name what it was called when this happened, rather than what it is called now — a
     *     destination somebody deleted last week still stopped answering on Tuesday
     * @param detail what it said when it did not, or null
     */
    @Schema(
            name = "ReachabilityEventSummary",
            description = "A change in whether something Keydra reaches was answering")
    public record ReachabilityEventSummary(
            String kind, Long subjectId, String name, Instant at, boolean ok, String detail) {}

    /**
     * The whole answer: who is here, and what they all rest on.
     *
     * @param choresStoppedSince when the last lease on the work that must happen once ran out,
     *     where nobody has taken one since and long enough has passed that it is not a handover.
     *     Null in every ordinary case, including the seconds between one instance giving the chores
     *     up and another claiming them. A page listing four healthy instances while none of them
     *     does any work is a page that is confidently wrong, and this is the field that stops it
     */
    @Schema(name = "InstanceHealth", description = "Who is running and what they depend on")
    public record InstanceHealth(
            List<InstanceSummary> instances,
            List<DependencyState> dependencies,
            Instant choresStoppedSince,
            @Schema(
                            description =
                                    "Things this deployment says twice, differently. Empty in the"
                                        + " ordinary case, and not the same question as a"
                                        + " dependency being unconfigured — that is a choice, and"
                                        + " these are contradictions.")
                    List<io.keydra.common.config.DeploymentNote> deployment) {}
}
