package io.keydra.engine.redis;

import io.keydra.connections.entity.ConnectionProfile;
import io.keydra.engine.KeyChange;
import io.keydra.engine.KeyspaceEvents;
import io.keydra.engine.KeyspaceNotice;
import io.keydra.engine.ServerSetting;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;

/**
 * Keyspace notifications, which RESP delivers as ordinary pub/sub messages.
 *
 * <p>Nothing is produced here. The server has published every mutation on {@code
 * __keyevent@<db>__:<event>} since Redis 2.8 and this listens; the whole of the work is choosing
 * which half to listen to, deciding whether the server is set to send it, and reading the key back
 * out of a message.
 */
@ApplicationScoped
public class RespKeyspaceEvents implements KeyspaceEvents {

    /**
     * The setting that decides whether any of this arrives.
     *
     * <p>Empty by default, and rightly: it costs the server a publish per write, which is a real
     * cost on a real cache and the wrong thing to impose on a server nobody is watching.
     */
    static final String SETTING = "notify-keyspace-events";

    /**
     * The two flags a watch needs, and no others.
     *
     * <p>{@code E} is the keyevent half — one channel per kind of event, carrying the key name. The
     * other half, {@code K}, is one channel per key carrying the kind, which is the same facts
     * arranged for the question "did this exact key change" rather than "what changed". A browser
     * asks the second.
     *
     * <p>{@code A} is Redis's own alias for every event class about a key. Written as the alias
     * rather than as the letters it stands for so that a server which learns a new class starts
     * sending it without Keydra being taught the letter — and deliberately not {@code m}, which the
     * alias also leaves out: key-miss is a message per read that found nothing, and on a cache a
     * miss is not an exception, it is the traffic.
     */
    private static final char EVENTS = 'E';

    private static final char ALL_CLASSES = 'A';

    /** How the server names the channels, once per database. */
    private static final String CHANNEL = "__keyevent@%d__:*";

    private final RespMessageBus messages;
    private final RespServerAdmin admin;

    @Inject
    RespKeyspaceEvents(RespMessageBus messages, RespServerAdmin admin) {
        this.messages = messages;
        this.admin = admin;
    }

    @Override
    public Multi<KeyChange> watch(ConnectionProfile profile, int database) {
        return messages.subscribe(profile, List.of(), List.of(CHANNEL.formatted(database)))
                .map(
                        message ->
                                new KeyChange(
                                        database, message.payload(), eventOf(message.channel())))
                .filter(change -> change.key() != null && !change.key().isBlank());
    }

    /**
     * The kind of event, which is the part of the channel name after the last colon.
     *
     * <p>Read off the channel rather than matched against a list. The vocabulary is the server's
     * and it grows — {@code new} arrived in Redis 7.4 — so a reader that only understood the words
     * it was taught would quietly drop whatever came next.
     */
    private static String eventOf(String channel) {
        if (channel == null) {
            return "";
        }
        int lastColon = channel.lastIndexOf(':');
        return lastColon < 0 ? channel : channel.substring(lastColon + 1);
    }

    @Override
    public Uni<KeyspaceNotice> notices(ConnectionProfile profile) {
        return admin.settings(profile, SETTING)
                .map(
                        settings -> {
                            String current = valueOf(settings);
                            return new KeyspaceNotice(current, delivers(current), union(current));
                        });
    }

    @Override
    public Uni<Void> announce(ConnectionProfile profile) {
        return notices(profile)
                .flatMap(
                        notice ->
                                notice.delivers()
                                        ? Uni.createFrom().voidItem()
                                        : admin.changeSetting(
                                                profile, SETTING, notice.wouldBecome()));
    }

    /** What the server says the setting is, or empty where it will not say. */
    private static String valueOf(List<ServerSetting> settings) {
        return settings.stream()
                .filter(setting -> SETTING.equalsIgnoreCase(setting.name()))
                .map(ServerSetting::value)
                .map(value -> value == null ? "" : value)
                .findFirst()
                .orElse("");
    }

    /**
     * Whether this value sends the events a watch needs.
     *
     * <p>{@code E} plus at least one class that covers keys. A value of {@code Elg} sends generic
     * and list events and nothing else, which is not everything but is not nothing either — so what
     * is checked is that something will arrive rather than that everything will. The offer to
     * change it is still made, and adds the rest.
     */
    private static boolean delivers(String value) {
        return value.indexOf(EVENTS) >= 0 && value.indexOf(ALL_CLASSES) >= 0;
    }

    /**
     * What the setting becomes: everything it said, plus the two flags.
     *
     * <p>A union rather than a replacement. Whoever set {@code Kx} on this server did it for a
     * reason — something is watching expiries — and switching it to {@code AE} would take that away
     * to add this. The flags are a set and the server treats them as one, so adding is safe and
     * ordering is not meaningful; they are sorted only so the value a page shows does not change
     * shape between two servers that mean the same thing.
     */
    private static String union(String value) {
        java.util.TreeSet<Character> flags = new java.util.TreeSet<>();
        value.chars()
                .filter(Character::isLetterOrDigit)
                .forEach(letter -> flags.add((char) letter));
        flags.add(ALL_CLASSES);
        flags.add(EVENTS);
        StringBuilder both = new StringBuilder();
        flags.forEach(both::append);
        return both.toString();
    }
}
