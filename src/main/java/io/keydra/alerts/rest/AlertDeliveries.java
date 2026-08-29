package io.keydra.alerts.rest;

import io.keydra.alerts.dto.AlertDtos.AlertDeliveryCheck;
import io.keydra.alerts.dto.AlertDtos.AlertDeliveryRequest;
import io.keydra.alerts.dto.AlertDtos.AlertDeliverySummary;
import io.keydra.alerts.service.AlertDeliveryService;
import io.keydra.alerts.service.InstanceNotices;
import io.keydra.authz.RequiresPermission;
import io.keydra.authz.entity.Permission;
import io.keydra.security.Audited;
import io.keydra.security.Roles;
import io.smallrye.mutiny.Uni;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;
import java.util.List;
import org.eclipse.microprofile.openapi.annotations.Operation;
import org.eclipse.microprofile.openapi.annotations.responses.APIResponse;
import org.eclipse.microprofile.openapi.annotations.tags.Tag;
import org.jboss.resteasy.reactive.RestResponse;

/**
 * The places alerts are sent, as rows.
 *
 * <p>An administrator's job. A delivery carries a credential to somewhere outside Keydra, and
 * somebody who may write a rule about one server is not thereby somebody who decides where that
 * server's troubles get announced.
 *
 * <p>A webhook address is treated as the credential it is: sent here once, never sent back, and
 * described afterwards by its host alone.
 */
@Path("/api/v1/alert-deliveries")
@Produces(MediaType.APPLICATION_JSON)
@Tag(name = "Alert deliveries", description = "Where alerts are sent")
@RolesAllowed(Roles.ADMIN)
@RequiresPermission(Permission.ALERT_DELIVERY_MANAGE)
public class AlertDeliveries {

    private final AlertDeliveryService service;
    private final InstanceNotices notices;

    @Inject
    AlertDeliveries(AlertDeliveryService service, InstanceNotices notices) {
        this.service = service;
        this.notices = notices;
    }

    @GET
    @Operation(summary = "Every configured delivery, and how many rules use it")
    @APIResponse(responseCode = "200", description = "The deliveries")
    public Uni<List<AlertDeliverySummary>> list() {
        return service.list();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(summary = "Add somewhere to send alerts")
    @APIResponse(responseCode = "201", description = "Added")
    @APIResponse(responseCode = "409", description = "That name is taken, or a field is missing")
    @Audited("alert.delivery.create")
    public Uni<RestResponse<AlertDeliverySummary>> create(@Valid AlertDeliveryRequest request) {
        return service.create(request).map(created -> RestResponse.status(Status.CREATED, created));
    }

    @PUT
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Change a delivery",
            description =
                    "An absent secret leaves the stored one alone; an empty one clears it. The"
                            + " webhook address is a secret by this rule, because it is one.")
    @APIResponse(responseCode = "200", description = "Changed")
    @APIResponse(responseCode = "409", description = "No such delivery, or a field is missing")
    @Audited("alert.delivery.update")
    public Uni<AlertDeliverySummary> update(
            @PathParam("id") Long id, @Valid AlertDeliveryRequest request) {
        return service.update(id, request);
    }

    @DELETE
    @Path("/{id}")
    @Operation(
            summary = "Remove a delivery",
            description =
                    "Refused while any rule still sends here. A rule whose delivery quietly became"
                            + " nothing is a rule that looks configured and reaches nobody.")
    @APIResponse(responseCode = "204", description = "Removed")
    @APIResponse(responseCode = "409", description = "Rules still send here")
    @Audited("alert.delivery.delete")
    public Uni<RestResponse<Void>> delete(@PathParam("id") Long id) {
        return service.delete(id).map(ignored -> RestResponse.noContent());
    }

    @GET
    @Path("/instance-notices")
    @Operation(
            summary = "Which destinations hear about Keydra itself",
            description =
                    "An identity provider or a backup destination that stops answering announces"
                            + " itself here. Empty is the default: the reachability is on the"
                            + " instances page either way.")
    @APIResponse(responseCode = "200", description = "The destination ids, in no order")
    public Uni<List<Long>> instanceNotices() {
        return notices.hearing();
    }

    @PUT
    @Path("/instance-notices")
    @Consumes(MediaType.APPLICATION_JSON)
    @Operation(
            summary = "Choose which destinations hear about Keydra itself",
            description = "The whole list, replacing what was there. Empty turns the notices off.")
    @APIResponse(responseCode = "200", description = "The list as it now stands")
    @Audited("alert.instance-notices")
    public Uni<List<Long>> instanceNotices(List<Long> deliveryIds) {
        return notices.hearing(deliveryIds == null ? List.of() : deliveryIds);
    }

    @POST
    @Path("/{id}/check")
    @Operation(
            summary = "Send a test message",
            description =
                    "Down the path a real alert takes, including the sentence it would carry — a"
                            + " check that only opened a connection would pass for a webhook that"
                            + " answers 404 and for a mail server that refuses the sender.")
    @APIResponse(responseCode = "200", description = "What the attempt found, working or not")
    @Audited("alert.delivery.check")
    public Uni<AlertDeliveryCheck> check(@PathParam("id") Long id) {
        return service.check(id);
    }
}
