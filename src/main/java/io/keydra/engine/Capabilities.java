package io.keydra.engine;

import java.util.Set;
import org.eclipse.microprofile.openapi.annotations.media.Schema;

/**
 * What a particular target can actually do.
 *
 * <p>Determined by asking the server, not by looking its product up in a table. Keydra talks to
 * Redis, Valkey and — in time — KeyDB, Dragonfly and Garnet, each of which implements a different
 * subset of the same command set, changes that subset between versions, and reports a {@code
 * redis_version} it is merely compatible with. A table of what each product supports would be wrong
 * the week after it was written, and wrong in the direction that matters: the UI would either hide
 * a feature the server has, or offer one it does not and fail on the click.
 *
 * @param features feature names this target supports, from {@link Feature}
 * @param detected false when the target could not be asked, in which case every feature is assumed
 *     present and an unsupported operation fails at the point of use instead of being hidden
 */
@Schema(name = "Capabilities", description = "What a target supports")
public record Capabilities(Set<String> features, boolean detected) {

    /** The features whose absence changes what the UI should offer. */
    public static final class Feature {

        private Feature() {}

        /** Duplicating a key in one server-side operation. */
        public static final String COPY_KEY = "copyKey";

        /** Moving a key to another name, which not every store can do at all. */
        public static final String RENAME_KEY = "renameKey";

        /**
         * Giving a key a time to live.
         *
         * <p>Absent for a store with no notion of expiry, and absent too for one whose expiry is
         * behind a setting the server was not started with — the reason is the target's, and what
         * the interface does about it is the same either way.
         */
        public static final String EXPIRY = "expiry";

        /** Measuring a key's memory, which the big-keys report is built on. */
        public static final String MEASURE_MEMORY = "measureMemory";

        /** The server's own record of slow commands. */
        public static final String SLOW_LOG = "slowLog";

        /** Listing and disconnecting clients. */
        public static final String CLIENT_LIST = "clientList";

        /** Streams, and therefore the stream editor. */
        public static final String STREAMS = "streams";

        /** Publish/subscribe. */
        public static final String PUB_SUB = "pubSub";

        /**
         * The store announcing its own mutations, and therefore a key list that stays true.
         *
         * <p>Beside {@link #PUB_SUB} rather than folded into it. RESP delivers one over the other,
         * which makes them look like one capability and they are not: carrying messages between
         * applications and telling anybody who asks that a key moved are different facilities, and
         * a store could offer the first without the second.
         */
        public static final String KEYSPACE_EVENTS = "keyspaceEvents";

        /** Cluster topology, which the topology view needs. */
        public static final String CLUSTER = "cluster";

        /** Sentinel discovery. */
        public static final String SENTINEL = "sentinel";

        /** Statistics, and therefore the dashboard. */
        public static final String METRICS = "metrics";

        /*
         * The five below are not probed for; they are answered by whether the engine offers the
         * capability at all. A store either has a command language or it does not, and that is a
         * fact about the implementation rather than about the server on the other end.
         */

        /** A command language, and therefore the console. */
        public static final String CONSOLE = "console";

        /** Watching commands as other clients send them. */
        public static final String COMMAND_STREAM = "commandStream";

        /** A user list of the store's own, and therefore the users page. */
        public static final String ACCESS_CONTROL = "accessControl";

        /** Handing a value over as bytes, and therefore import, export and the fast migration. */
        public static final String TRANSFER = "transfer";

        /** Reading and changing the server's own settings. */
        public static final String ADMIN = "admin";

        /**
         * Being able to describe how a target is arranged at all.
         *
         * <p>Not the same as {@link #CLUSTER}, which is this server answering the CLUSTER command.
         * A standalone Redis has no cluster and still has a shape worth drawing — one server, its
         * replicas, what it supports. A store with no notion of nodes has neither.
         */
        public static final String TOPOLOGY = "topology";
    }

    /** Everything supported, for a target that could not be asked. */
    public static Capabilities assumed() {
        return new Capabilities(
                Set.of(
                        Feature.COPY_KEY,
                        Feature.RENAME_KEY,
                        Feature.EXPIRY,
                        Feature.MEASURE_MEMORY,
                        Feature.SLOW_LOG,
                        Feature.CLIENT_LIST,
                        Feature.STREAMS,
                        Feature.PUB_SUB,
                        Feature.CLUSTER,
                        Feature.SENTINEL,
                        Feature.METRICS),
                false);
    }

    public boolean supports(String feature) {
        return features.contains(feature);
    }
}
