package io.keydra.authz.dto;

import java.time.Instant;
import java.util.List;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;

/**
 * One attempt to sign in, as it is shown.
 *
 * <p>What it deliberately does not carry is an address. The row does not hold one either — the
 * network is the address with its last part removed, and the country was resolved while the request
 * was in flight from an address that was then dropped. Somebody reading their own activity needs to
 * recognise where they were, not to be handed a log of where they have been.
 *
 * @param network the address with its last part removed, or null where none was known
 * @param country two letters, where this instance has a geography database
 * @param anomalies what was unusual about it, which for almost every row is nothing
 */
@Name("SignInActivity")
@Description("One attempt to sign in, and what was unusual about it")
public record SignInActivity(
        @NonNull Long id,
        @NonNull @Description("The name as it was typed") String username,
        @NonNull @Description("How it ended") String outcome,
        @NonNull @Description("A password here, or the provider that vouched") String method,
        @NonNull Instant at,
        @Description("The network it came from, not the address") String network,
        @Description("Two letters, where this instance can place an address") String country,
        @Description("The browser as it described itself") String userAgent,
        @NonNull @Description("What was unusual about it, usually nothing")
                List<String> anomalies) {}
