package io.keydra.tunnels.mapper;

import io.keydra.tunnels.dto.TunnelDtos.TunnelRequest;
import io.keydra.tunnels.dto.TunnelDtos.TunnelSummary;
import io.keydra.tunnels.entity.SshTunnel;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;

/**
 * Translates between a tunnel row and its wire shape.
 *
 * <p>Generated, like the other two mappers and for the same reason: a field somebody forgets to
 * copy fails the build rather than arriving as null. The rules that are not plain copies — secrets
 * becoming booleans on the way out, and keep-or-clear on the way in — are written out.
 */
@Mapper(componentModel = "jakarta")
public interface TunnelMapper {

    @Mapping(target = "hasPassword", source = "tunnel.password", qualifiedByName = "isPresent")
    @Mapping(target = "hasPrivateKey", source = "tunnel.privateKey", qualifiedByName = "isPresent")
    @Mapping(
            target = "verifiesHostKey",
            source = "tunnel.hostKeyFingerprint",
            qualifiedByName = "isPresent")
    // The entity's own one-liner, so a list and a log say the same thing about a tunnel.
    @Mapping(target = "describedAs", expression = "java(tunnel.describedAs())")
    TunnelSummary toSummary(SshTunnel tunnel, long usedBy);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "password", ignore = true)
    @Mapping(target = "privateKey", ignore = true)
    @Mapping(target = "passphrase", ignore = true)
    void apply(TunnelRequest request, @MappingTarget SshTunnel tunnel);

    /**
     * Secrets: absent means keep, empty means clear.
     *
     * <p>The API never returns a stored secret, so an edit form arrives with the field empty;
     * treating that as "clear it" would drop the jump host's key every time somebody corrected a
     * label — and take every target behind it offline.
     */
    @AfterMapping
    default void applySecrets(TunnelRequest request, @MappingTarget SshTunnel tunnel) {
        if (request.password() != null) {
            tunnel.password = request.password().isEmpty() ? null : request.password();
        }
        if (request.privateKey() != null) {
            tunnel.privateKey = request.privateKey().isEmpty() ? null : request.privateKey();
        }
        if (request.passphrase() != null) {
            tunnel.passphrase = request.passphrase().isEmpty() ? null : request.passphrase();
        }
    }

    /** A tunnel with no port stated uses SSH's own. */
    @AfterMapping
    default void applyPort(TunnelRequest request, @MappingTarget SshTunnel tunnel) {
        if (request.port() <= 0) {
            tunnel.port = 22;
        }
    }

    @Named("isPresent")
    static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
