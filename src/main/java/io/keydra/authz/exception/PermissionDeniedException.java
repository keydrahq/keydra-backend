package io.keydra.authz.exception;

import io.keydra.authz.entity.Permission;

/**
 * Raised when the caller does not hold what an endpoint requires.
 *
 * <p>Says which permission was missing rather than "forbidden". Somebody who has been refused needs
 * to be able to ask for the right thing, and an administrator reading the audit log needs to know
 * what to grant — neither is served by a bare 403.
 */
public class PermissionDeniedException extends RuntimeException {

    private final transient Permission permission;

    public PermissionDeniedException(Permission permission, Long connectionId) {
        super(
                connectionId == null
                        ? "This requires " + permission.id()
                        : "This requires " + permission.id() + " on connection " + connectionId);
        this.permission = permission;
    }

    public Permission permission() {
        return permission;
    }
}
