package io.keydra.common.openapi;

import java.util.Map;
import org.eclipse.microprofile.openapi.OASFilter;
import org.eclipse.microprofile.openapi.models.OpenAPI;

/**
 * Adds the {@code x-logo} vendor extension to the generated OpenAPI document.
 *
 * <p>ReDoc, Stoplight and similar renderers use it to brand the API reference. Swagger UI ignores
 * the extension and is branded through {@code META-INF/branding/} instead.
 *
 * <p>Registered via {@code mp.openapi.filter} in {@code application.properties}.
 */
public class OpenApiBranding implements OASFilter {

    /** Served from {@code META-INF/resources/branding/} by the static resource handler. */
    static final String LOGO_URL = "/branding/keydra-logo-light.svg";

    static final String EXTENSION_NAME = "x-logo";

    @Override
    public void filterOpenAPI(OpenAPI openAPI) {
        if (openAPI.getInfo() == null) {
            return;
        }
        openAPI.getInfo()
                .addExtension(EXTENSION_NAME, Map.of("url", LOGO_URL, "altText", "Keydra"));
    }
}
