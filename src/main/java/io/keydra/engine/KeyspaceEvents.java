package io.keydra.engine;

import io.keydra.connections.entity.ConnectionProfile;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;

/**
 * A store telling anybody who asks that a key changed.
 *
 * <p>Optional for the reason {@link CommandConsole} and {@link MessageBus} are: most key-value
 * stores have no such thing, and an engine that cannot do this should say so in the type rather
 * than at the first call.
 *
 * <p>Separate from {@link MessageBus} although RESP delivers it over exactly that. Carrying
 * messages between applications and announcing one's own mutations are two facilities that happen
 * to share a transport here, and an engine could plainly have the first without the second — so
 * they are two capabilities and a target can offer either alone.
 */
public interface KeyspaceEvents {

    /**
     * Watches one database and streams what changes in it.
     *
     * <p>The stream is the watch: cancelling it stops listening and gives back whatever the store
     * needed to hold it open. One database rather than all of them because a browser is in one, and
     * a watch on sixteen would be fifteen streams nobody reads.
     */
    Multi<KeyChange> watch(ConnectionProfile profile, int database);

    /**
     * Whether this target currently announces its changes, and what it would take.
     *
     * <p>Asked rather than assumed. The setting is the server's, it can be changed by anything with
     * an administrative connection, and a browser that assumed it was still on would show a list
     * that stopped updating without saying so.
     */
    Uni<KeyspaceNotice> notices(ConnectionProfile profile);

    /**
     * Turns the announcements on, keeping whatever else the setting already said.
     *
     * <p>Changing a running server's configuration, which is a different act from watching and
     * carries a different permission. The value written is {@link KeyspaceNotice#wouldBecome()} — a
     * union, so a server already announcing something extra goes on announcing it.
     */
    Uni<Void> announce(ConnectionProfile profile);
}
