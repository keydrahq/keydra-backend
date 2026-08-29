package io.keydra.engine;

import io.keydra.connections.entity.ConnectionProfile;
import io.smallrye.mutiny.Uni;
import java.util.List;

/**
 * A store's own user list, for stores that keep one.
 *
 * <p>Optional like the other capabilities. This one is a genuine dividing line: a store with no
 * notion of users has nothing to manage here, and Redis itself had none before 6.
 */
public interface AccessControl {

    /** The users the store knows, with what each may do. */
    Uni<List<AclUser>> users(ConnectionProfile profile);

    /**
     * Creates or replaces one user.
     *
     * @param rules the store's own rule syntax, passed through unaltered
     */
    Uni<Void> setUser(ConnectionProfile profile, String username, List<String> rules);

    /**
     * @return true when the store had a user of that name
     */
    Uni<Boolean> deleteUser(ConnectionProfile profile, String username);

    /** The permission categories the store defines, for a UI that offers rather than asks. */
    Uni<List<String>> categories(ConnectionProfile profile);
}
