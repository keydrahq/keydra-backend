package io.keydra.about.rest;

import io.keydra.about.dto.AboutResponse;
import io.keydra.about.dto.BuildDetails;
import io.keydra.about.dto.InstanceDetails;
import io.keydra.about.service.BuildInfo;
import io.keydra.cluster.service.Leadership;
import io.keydra.telemetry.dto.ObservabilityDetails;
import io.keydra.telemetry.service.TelemetrySettings;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;

/** Application identity and build metadata. */
@Path("/api/v1/about")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "About", description = "Application identity and build metadata")
public class About {

    private final BuildInfo buildInfo;
    private final Leadership leadership;
    private final TelemetrySettings telemetry;
    private final SecurityIdentity identity;

    @Inject
    About(
            BuildInfo buildInfo,
            Leadership leadership,
            TelemetrySettings telemetry,
            SecurityIdentity identity) {
        this.buildInfo = buildInfo;
        this.leadership = leadership;
        this.telemetry = telemetry;
        this.identity = identity;
    }

    @GET
    @Operation(summary = "Get application name, version and build metadata")
    @APIResponse(responseCode = "200", description = "Application metadata")
    public Uni<AboutResponse> get() {
        // Not to a stranger. This endpoint answers before anybody has signed in — the login
        // page shows the version — and an instance id is usually a host or a pod name, as is
        // the address of a collector, which is nobody's business from outside. The version is
        // deliberately still given: it is what somebody reporting a problem needs, and it is
        // on the login page already.
        if (identity.isAnonymous()) {
            return Uni.createFrom().item(describe((InstanceDetails) null));
        }
        // Who holds the chores is read rather than remembered: this instance knows whether it
        // holds them, and the useful answer when it does not is the name of the one that does.
        return leadership
                .holder()
                .onFailure()
                .recoverWithItem((String) null)
                .map(
                        chores ->
                                describe(
                                        new InstanceDetails(
                                                leadership.instanceId(),
                                                leadership.isLeader(),
                                                chores)));
    }

    private AboutResponse describe(InstanceDetails instance) {
        ObservabilityDetails exports = instance == null ? null : telemetry.describe();
        return new AboutResponse(
                buildInfo.name(),
                buildInfo.version(),
                new BuildDetails(
                        buildInfo.timestamp(),
                        buildInfo.commit(),
                        buildInfo.javaVersion(),
                        buildInfo.quarkusVersion()),
                instance,
                exports);
    }
}
