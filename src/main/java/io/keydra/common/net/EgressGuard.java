package io.keydra.common.net;

import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import io.vertx.core.Context;
import io.vertx.core.Vertx;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Decides whether Keydra will make a request to an address somebody typed.
 *
 * <p>Several things here take a URL from a form and fetch it: a webhook an alert posts to, the
 * issuer of an identity provider, the endpoint of an object store. Each of those is a request made
 * by the server, from inside whatever network the server is in, with whatever the server can reach.
 * That is server-side request forgery, and it is old enough that the interesting part is not the
 * idea but which address is worth the most: on every large cloud it is {@code 169.254.169.254},
 * which answers unauthenticated with the credentials of the machine's role. A webhook is the
 * shortest path from "may configure an alert" to "holds the deployment's cloud credentials", and
 * configuring an alert is an operator's to do rather than an administrator's.
 *
 * <p>So link-local is refused outright and is not configurable. Nothing legitimate posts an alert
 * to a metadata service, and a setting that allowed it would exist only to be turned on by somebody
 * who did not know what it was for.
 *
 * <p>Loopback is refused by default and can be allowed, because a deployment that runs a receiver
 * beside Keydra is a real arrangement rather than a mistake. Private ranges are allowed by default
 * and can be refused, and that way round on purpose: a self-hosted chat server on a private address
 * is the ordinary case for a webhook, and refusing it by default would mean the setting is turned
 * off on the first day by everybody, which is worse than it being on.
 *
 * <p>What this does not close: a name that resolves to something allowed now and something else
 * when the request is actually made. Closing that means resolving once and carrying the address
 * into the connection, which every client here builds for itself. The check is worth having without
 * it — the realistic version of this is somebody pasting an address into a form, not somebody
 * running a name server for the occasion — and the gap is written down rather than implied.
 */
@ApplicationScoped
public class EgressGuard {

    private final boolean allowLoopback;
    private final boolean allowPrivate;
    private final List<String> alwaysAllowedHosts;

    @Inject
    EgressGuard(
            @ConfigProperty(name = "keydra.egress.allow-loopback") boolean allowLoopback,
            @ConfigProperty(name = "keydra.egress.allow-private") boolean allowPrivate,
            @ConfigProperty(name = "keydra.egress.allowed-hosts") Optional<List<String>> allowed) {
        this.allowLoopback = allowLoopback;
        this.allowPrivate = allowPrivate;
        this.alwaysAllowedHosts = allowed.orElse(List.of());
    }

    /**
     * Passes, or fails with {@link BlockedAddressException}.
     *
     * <p>Resolution is a blocking call, which is why this answers a {@code Uni} rather than a
     * boolean: the name lookup runs on a worker and everything after it comes back to the context
     * it started on. Both halves matter. Doing the lookup on the event loop stalls it; leaving the
     * answer on the worker hands the rest of the chain — which here is a Hibernate transaction — to
     * a thread its session was not opened on, and Hibernate Reactive says so outright rather than
     * misbehaving quietly.
     *
     * <p>A literal address needs no resolver and takes the same path anyway. The cost is two thread
     * hops on a form submission, which is not a thing anybody does in a loop.
     */
    public Uni<Void> check(String url) {
        Context origin = Vertx.currentContext();
        Uni<Void> resolved =
                Uni.createFrom()
                        .item(() -> url)
                        .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                        .invoke(this::checkBlocking)
                        .replaceWithVoid();
        return origin == null
                ? resolved
                : resolved.emitOn(work -> origin.runOnContext(ignored -> work.run()));
    }

    /** The same decision, for a caller already on a worker thread. */
    public void checkBlocking(String url) {
        URI parsed = parse(url);
        String scheme = parsed.getScheme() == null ? "" : parsed.getScheme().toLowerCase();
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new BlockedAddressException(
                    "Keydra posts to http and https addresses. Change the scheme and try again.");
        }
        String host = parsed.getHost();
        if (host == null || host.isBlank()) {
            throw new BlockedAddressException("That address has no host. Check it and try again.");
        }
        if (alwaysAllowedHosts.stream().anyMatch(one -> one.equalsIgnoreCase(host))) {
            return;
        }
        for (InetAddress address : resolve(host)) {
            refuseIfUnreachable(host, address);
        }
    }

    /** Whether this address is one Keydra will reach, without the exception. */
    public boolean permits(String url) {
        try {
            checkBlocking(url);
            return true;
        } catch (BlockedAddressException refused) {
            return false;
        }
    }

    private void refuseIfUnreachable(String host, InetAddress address) {
        if (address.isLinkLocalAddress() || isMetadata(address)) {
            // Named, because this is the one an administrator should recognise on sight.
            throw new BlockedAddressException(
                    "Keydra does not make requests to link-local addresses, which is where cloud"
                            + " machines keep their credentials. Use an address reachable from"
                            + " outside the machine.");
        }
        if (!allowLoopback && address.isLoopbackAddress()) {
            throw new BlockedAddressException(
                    "Keydra does not make requests to itself or to "
                            + host
                            + ". Set keydra.egress.allow-loopback if this deployment means it.");
        }
        if (!allowPrivate && (address.isSiteLocalAddress() || isUniqueLocal(address))) {
            throw new BlockedAddressException(
                    "Keydra does not make requests to private addresses on this instance. Use a"
                            + " reachable address, or add "
                            + host
                            + " to keydra.egress.allowed-hosts.");
        }
        if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
            throw new BlockedAddressException(
                    "That is not an address a request can be made to. Check it and try again.");
        }
    }

    /**
     * The two forms the cloud metadata endpoint takes.
     *
     * <p>{@code isLinkLocalAddress} already covers 169.254/16 and fe80::/10. This is for the IPv6
     * address the same service answers on, which is unique-local rather than link-local and would
     * otherwise be allowed anywhere private addresses are.
     */
    private static boolean isMetadata(InetAddress address) {
        if (address instanceof Inet6Address) {
            byte[] bytes = address.getAddress();
            // fd00:ec2::254 — the form AWS answers on over IPv6.
            return (bytes[0] & 0xFF) == 0xFD
                    && (bytes[1] & 0xFF) == 0x00
                    && (bytes[2] & 0xFF) == 0xEC
                    && (bytes[3] & 0xFF) == 0x02;
        }
        return false;
    }

    /** fc00::/7, which Java has no predicate for and which is the IPv6 answer to site-local. */
    private static boolean isUniqueLocal(InetAddress address) {
        if (!(address instanceof Inet6Address)) {
            return false;
        }
        return (address.getAddress()[0] & 0xFE) == 0xFC;
    }

    private static URI parse(String url) {
        try {
            return URI.create(url.trim());
        } catch (IllegalArgumentException notAUrl) {
            throw new BlockedAddressException("That is not an address. Check it and try again.");
        }
    }

    private static InetAddress[] resolve(String host) {
        try {
            InetAddress[] resolved = InetAddress.getAllByName(host);
            if (resolved.length == 0) {
                throw new BlockedAddressException(
                        "Keydra could not find " + host + ". Check the address and try again.");
            }
            return resolved;
        } catch (java.net.UnknownHostException unknown) {
            throw new BlockedAddressException(
                    "Keydra could not find " + host + ". Check the address and try again.");
        }
    }
}
