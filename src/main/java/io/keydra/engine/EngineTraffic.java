package io.keydra.engine;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.concurrent.atomic.AtomicLong;

/**
 * How much this instance has said to the servers it watches.
 *
 * <p>One number, counted where the commands actually leave. It is the main thing Keydra does, and
 * until this existed there was no way to see it happening — the metrics page shows what a *target*
 * is doing, which is a different question from what this Keydra is asking of it.
 *
 * <p>Cumulative and nothing else. A rate is two readings and a subtraction, and whoever wants one
 * can do that arithmetic against a clock they trust; a counter here that reset itself on a schedule
 * would be a second clock nobody asked for.
 *
 * <p>What it counts is what leaves on a pooled connection, which is everything Keydra asks of a
 * target while working: browsing, reading a value, sampling metrics, a console command. A
 * connection test is not counted, because it opens a throwaway connection on purpose — so that
 * probing an unsaved profile leaves no trace — and one INFO on a button press is not the traffic
 * anybody is watching for.
 *
 * <p>All three engines increment it now, each at the one place a command is still one command: RESP
 * at the pooled connection it leaves on, TiKV at the single call that goes off the event loop,
 * Aerospike where the client for a profile is asked for — that last one because Aerospike has no
 * pooled connection to count at, and every call site there issues exactly one operation with what
 * it gets back.
 */
@ApplicationScoped
public class EngineTraffic {

    private final AtomicLong commands = new AtomicLong();

    /** Called where a command is handed to a client, which is the last place it is still one. */
    public void sent() {
        commands.incrementAndGet();
    }

    /**
     * The same, for a pipeline.
     *
     * <p>Counted as the commands it holds rather than as one, because that is what the server is
     * asked to do: a page of two hundred keys is two hundred questions that happen to travel
     * together, and calling it one would make the busiest thing Keydra does look like the quietest.
     */
    public void sent(int howMany) {
        commands.addAndGet(howMany);
    }

    public long commandCount() {
        return commands.get();
    }
}
