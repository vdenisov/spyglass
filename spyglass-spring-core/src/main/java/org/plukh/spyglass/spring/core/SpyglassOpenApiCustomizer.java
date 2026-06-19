package org.plukh.spyglass.spring.core;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.apache.commons.lang3.StringUtils;
import org.springdoc.core.customizers.OpenApiCustomizer;

/**
 * Additively customizes the generated OpenAPI document for Spyglass, rather than replacing the whole
 * {@code OpenAPI} bean — so it composes with springdoc's defaults and any customizations a consuming
 * service supplies. It:
 *
 * <ul>
 *   <li>registers an {@code Authorization} header security scheme (and requires it), so the explorer
 *       can send a generated auth header;</li>
 *   <li>exposes {@code x-service-name} (the standard {@code spring.application.name}) as an
 *       {@code info} extension;</li>
 *   <li>sets a default title only when the consumer hasn't provided one.</li>
 * </ul>
 *
 * <p>This is the vendor-neutral core customizer; consumer-specific {@code info} extensions
 * ({@code x-spyglass-extensions}) are added by an extension pack's own additive customizer, not here.
 *
 * <p>It targets the springdoc-<b>common</b> {@link OpenApiCustomizer} SPI and the
 * {@code io.swagger.v3.oas.models} object model, both shared across the servlet and reactive stacks
 * and across springdoc 2.x / 3.x.
 */
public class SpyglassOpenApiCustomizer implements OpenApiCustomizer {

    private static final String SECURITY_SCHEME = "Authorization Header";

    /** springdoc's placeholder title when none is configured; treated as "no title set". */
    private static final String SPRINGDOC_DEFAULT_TITLE = "OpenAPI definition";

    private final String applicationName;

    public SpyglassOpenApiCustomizer(String applicationName) {
        this.applicationName = applicationName;
    }

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getInfo() == null) {
            openApi.setInfo(new Info());
        }
        var info = openApi.getInfo();
        // Default the title to "<service> API" unless the consumer set a real one. springdoc always
        // pre-fills its own placeholder ("OpenAPI definition"), so treat that as unset too.
        if (StringUtils.isBlank(info.getTitle()) || SPRINGDOC_DEFAULT_TITLE.equals(info.getTitle())) {
            info.setTitle(StringUtils.trimToEmpty(applicationName) + " API");
        }
        info.addExtension("x-service-name", applicationName);

        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        openApi.getComponents().addSecuritySchemes(SECURITY_SCHEME,
                new SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .in(SecurityScheme.In.HEADER)
                        .name("Authorization"));
        openApi.addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
    }
}
