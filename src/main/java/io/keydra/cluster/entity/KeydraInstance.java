package io.keydra.cluster.entity;

import io.keydra.cluster.persistence.TargetListConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.List;

/**
 * One running Keydra, as it says of itself.
 *
 * <p>The lease table already answers "who does the chores", and that is a different question from
 * "who is here". A rolling upgrade with two instances up has one leader and two instances; an
 * instance that has crashed has neither. Until this existed there was no way to see the second
 * number at all — Keydra watched everybody's servers and could not say how many of itself were
 * running.
 *
 * <p>Written on the beat that renews the lease rather than on a timer of its own, because they are
 * the same fact arriving at the same moment: an instance that can still write here is an instance
 * that can still renew, and one that cannot do either has already stopped being here.
 *
 * <p>{@link #lastSeenAt} is written by the database's clock for the same reason the lease's expiry
 * is: two machines whose clocks differ by a minute still agree about which of them was heard from
 * more recently, because neither of them is the one being asked.
 */
@Entity
@Table(
        name = "keydra_instance",
        indexes = {@Index(name = "idx_keydra_instance_seen", columnList = "last_seen_at")})
public class KeydraInstance {

    /** What the instance calls itself: the pod or host name plus a random suffix. */
    @Id
    @Column(length = 64)
    public String id;

    /** The build it is running, so a rolling upgrade is visible as one. */
    @Column(nullable = false, length = 64)
    public String version;

    /** The commit that build came from, which is what a version alone does not pin down. */
    @Column(length = 64)
    public String commit;

    @Column(name = "started_at", nullable = false)
    public Instant startedAt = Instant.now();

    @Column(name = "last_seen_at", nullable = false)
    public Instant lastSeenAt = Instant.now();

    /**
     * What it has put on the notification bus, and what it has taken off.
     *
     * <p>Here rather than in each instance's own memory because the question is about all of them:
     * an instance can only count its own, and a page showing one instance's traffic while calling
     * it the fleet's would be worse than showing none.
     *
     * <p>Cumulative. Two readings give a rate; one gives a number nothing can disagree about.
     */
    @Column(nullable = false)
    public long published;

    @Column(nullable = false)
    public long received;

    /**
     * How many commands it has sent to the servers it watches.
     *
     * <p>The other traffic worth drawing, and the busier of the two by a long way: the bus carries
     * a handful of notifications and this carries every scan, every INFO, every sample.
     */
    @Column(nullable = false)
    public long commands;

    /**
     * How many browsers this instance is talking to.
     *
     * <p>Where a load balancer's decisions show up, and only meaningful read across the fleet:
     * twelve here and none next door is not a busy instance, it is a balancer sending everything
     * one way.
     */
    @Column(nullable = false)
    public int sockets;

    /**
     * Connections held open against a target because somebody is looking at them — subscriptions
     * and command watches.
     *
     * <p>Separate from {@link #sockets} because they answer different questions: one is how many
     * visitors there are, the other is how many of them are watching something that does not stop
     * when a request does.
     */
    @Column(nullable = false)
    public int streams;

    /** Long work under way: a keyspace being walked, a tunnel being held. */
    @Column(nullable = false)
    public int jobs;

    /**
     * Which targets this instance holds clients for.
     *
     * <p>Ids rather than a count, because the question phase 39 left was <em>which</em> — and a
     * target three instances are watching is three pools against one server.
     */
    @Column(name = "watching", length = 4000)
    @Convert(converter = TargetListConverter.class)
    public List<Long> watching = List.of();

    /**
     * Whether somebody has asked this instance to stop taking new work.
     *
     * <p>The only column here the instance does not write about itself. Everything else on the row
     * is a report; this is an instruction, and it reaches its subject the only way anything can —
     * through the row, because there is no instance to send a request to.
     *
     * <p>Which is why the announcement leaves it alone: an instance writing what it currently
     * believes would undo the instruction on the next beat. It is cleared once, by the first
     * announcement a process makes, because a drain applies to a running process rather than to a
     * name that comes back.
     */
    @Column(nullable = false)
    public boolean draining;

    /**
     * Whether somebody has already said this instance stopped answering.
     *
     * <p>Written by whichever instance notices, with an update that names the value it expects to
     * find — so three instances seeing the same silence send one message between them, and none of
     * them has to be the one in charge. That is the point rather than an optimisation: what is
     * being watched for is nobody being in charge.
     *
     * <p>Cleared again if this name starts beating, which only happens where the id was configured.
     * A name Keydra makes up carries something random after it, so what replaces a dead instance is
     * a different instance with a different row.
     */
    @Column(name = "absence_announced", nullable = false)
    public boolean absenceAnnounced = false;
}
