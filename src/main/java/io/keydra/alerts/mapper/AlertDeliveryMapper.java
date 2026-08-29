package io.keydra.alerts.mapper;

import io.keydra.alerts.dto.AlertDtos.AlertDeliveryRequest;
import io.keydra.alerts.dto.AlertDtos.AlertDeliverySummary;
import io.keydra.alerts.entity.AlertDelivery;
import java.net.URI;
import org.mapstruct.AfterMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingTarget;
import org.mapstruct.Named;

/**
 * Translates between a delivery row and its wire shape.
 *
 * <p>Generated, like every other mapper here: a field added to one side and forgotten on the other
 * is a build failure rather than a null somebody discovers in production.
 *
 * <p>Three rules are not plain copies. The secrets — and the webhook address is one — become
 * booleans on the way out, they follow keep-or-clear on the way in, and the one-line description is
 * built from whichever fields the kind actually uses.
 */
@Mapper(componentModel = "jakarta")
public interface AlertDeliveryMapper {

    @Mapping(target = "hasUrl", source = "delivery.url", qualifiedByName = "isPresent")
    @Mapping(target = "hasSecret", source = "delivery.headerValue", qualifiedByName = "isPresent")
    @Mapping(target = "hasPassword", source = "delivery.password", qualifiedByName = "isPresent")
    @Mapping(target = "hasApiToken", source = "delivery.apiToken", qualifiedByName = "isPresent")
    @Mapping(target = "describedAs", source = "delivery", qualifiedByName = "describe")
    @Mapping(target = "usedByRules", source = "usedByRules")
    AlertDeliverySummary toSummary(AlertDelivery delivery, int usedByRules);

    /**
     * Applies a request onto an existing delivery.
     *
     * <p>The id belongs to persistence, the two flags have defaults that an absent value must not
     * overwrite, the secrets have their own rule, and the host is derived rather than sent — so all
     * of them are excluded here and set below.
     */
    @Mapping(target = "id", ignore = true)
    @Mapping(target = "enabled", ignore = true)
    @Mapping(target = "smtpTls", ignore = true)
    @Mapping(target = "url", ignore = true)
    @Mapping(target = "urlHost", ignore = true)
    @Mapping(target = "headerValue", ignore = true)
    @Mapping(target = "password", ignore = true)
    @Mapping(target = "apiToken", ignore = true)
    void apply(AlertDeliveryRequest request, @MappingTarget AlertDelivery delivery);

    /** The flags, where absent means "leave it as it was". */
    @AfterMapping
    default void applyFlags(AlertDeliveryRequest request, @MappingTarget AlertDelivery delivery) {
        if (request.enabled() != null) {
            delivery.enabled = request.enabled();
        }
        if (request.smtpTls() != null) {
            delivery.smtpTls = request.smtpTls();
        }
    }

    /**
     * Secrets: absent means keep, empty means clear.
     *
     * <p>The address is one of them. The API never returns it, so an edit form arrives with the
     * field empty — and treating that as "clear it" would silence every rule pointing at it the
     * next time somebody corrected a label.
     */
    @AfterMapping
    default void applySecrets(AlertDeliveryRequest request, @MappingTarget AlertDelivery delivery) {
        if (request.url() != null) {
            delivery.url = request.url().isEmpty() ? null : request.url().trim();
            delivery.urlHost = hostOf(delivery.url);
        }
        if (request.headerValue() != null) {
            delivery.headerValue = request.headerValue().isEmpty() ? null : request.headerValue();
        }
        if (request.password() != null) {
            delivery.password = request.password().isEmpty() ? null : request.password();
        }
        if (request.apiToken() != null) {
            delivery.apiToken = request.apiToken().isEmpty() ? null : request.apiToken().trim();
        }
    }

    @Named("isPresent")
    static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Where this points, in one line.
     *
     * <p>For a webhook that is the host and nothing else: the rest of the address is the token.
     */
    @Named("describe")
    static String describe(AlertDelivery delivery) {
        return switch (delivery.kind) {
            case WEBHOOK -> delivery.urlHost == null ? "(no address)" : delivery.urlHost;
            case EMAIL ->
                    (delivery.toAddresses == null ? "(nobody)" : delivery.toAddresses)
                            + " via "
                            + (delivery.smtpHost == null ? "(no server)" : delivery.smtpHost);
            // The recipient is the whole description for the three chat tools: the token says
            // who is speaking and is not shown, and the kind is already a column beside this.
            case TELEGRAM ->
                    delivery.recipient == null ? "(no chat)" : "chat " + delivery.recipient;
            case SLACK -> delivery.recipient == null ? "(no channel)" : delivery.recipient;
            case WHATSAPP -> delivery.recipient == null ? "(no number)" : delivery.recipient;
        };
    }

    /**
     * The host of an address, or null when it is not one this can post to.
     *
     * <p>Kept beside the encrypted address so a list can say where alerts go without decrypting
     * anything, and so nothing downstream has to be trusted to redact the rest.
     *
     * <p>Named, and it has to be. An unqualified String-to-String method in a mapper is a
     * conversion MapStruct will apply to every string it maps — which it did, and which turned
     * every name in this file into the host of a URL that was not one.
     */
    @Named("hostOf")
    static String hostOf(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        try {
            URI parsed = URI.create(url.trim());
            return parsed.getHost();
        } catch (IllegalArgumentException notAUrl) {
            return null;
        }
    }
}
