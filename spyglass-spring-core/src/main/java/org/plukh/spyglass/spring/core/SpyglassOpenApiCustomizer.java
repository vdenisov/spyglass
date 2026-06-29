package org.plukh.spyglass.spring.core;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.jspecify.annotations.Nullable;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.util.StringUtils;

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
 *   <li>sets a default title only when the consumer hasn't provided one;</li>
 *   <li>fills {@code info.version} from the build version only when the consumer hasn't set a real one
 *       (springdoc's {@code "v0"} placeholder counts as unset). This makes the spec carry a meaningful
 *       version automatically, and gives the explorer's update check a value that changes per build — see
 *       {@code SpyglassCoreConfiguration}, which sources it from {@code BuildProperties} and lets a
 *       consumer disable it.</li>
 * </ul>
 *
 * <p>This is the vendor-neutral core customizer; consumer-specific {@code info} extensions
 * ({@code x-spyglass-extensions}) are added by an extension pack's own additive customizer, not here.
 *
 * <p>It targets the springdoc-<b>common</b> {@link OpenApiCustomizer} SPI and the
 * {@code io.swagger.v3.oas.models} object model, both shared across the servlet and reactive stacks
 * and across springdoc 2.x / 3.x.
 *
 * <p>The check-then-act guards (skip the scheme/requirement when already present) make re-invocation
 * idempotent but are not atomic. That is safe because springdoc builds and customizes each group's
 * {@code OpenAPI} on a single thread; this customizer assumes that single-threaded invocation rather
 * than synchronizing on the shared model.
 */
public class SpyglassOpenApiCustomizer implements OpenApiCustomizer {

    private static final String SECURITY_SCHEME = "Authorization Header";

    /** springdoc's placeholder title when none is configured; treated as "no title set". */
    private static final String SPRINGDOC_DEFAULT_TITLE = "OpenAPI definition";

    /** springdoc's placeholder version when none is configured; treated as "no version set". */
    private static final String SPRINGDOC_DEFAULT_VERSION = "v0";

    private final String applicationName;

    /** The build version to fill into {@code info.version} when it's unset, or {@code null} to leave it. */
    private final @Nullable String buildVersion;

    public SpyglassOpenApiCustomizer(String applicationName) {
        this(applicationName, null);
    }

    public SpyglassOpenApiCustomizer(String applicationName, @Nullable String buildVersion) {
        this.applicationName = applicationName;
        this.buildVersion = buildVersion;
    }

    @Override
    public void customise(OpenAPI openApi) {
        if (openApi.getInfo() == null) {
            openApi.setInfo(new Info());
        }
        var info = openApi.getInfo();
        // Default the title to "<service> API" unless the consumer set a real one. springdoc always
        // pre-fills its own placeholder ("OpenAPI definition"), so treat that as unset too. With no app
        // name, fall back to a bare "API" rather than a leading-space " API".
        if (!StringUtils.hasText(info.getTitle()) || SPRINGDOC_DEFAULT_TITLE.equals(info.getTitle())) {
            var name = applicationName == null ? "" : applicationName.strip();
            info.setTitle(name.isEmpty() ? "API" : name + " API");
        }
        // Fill the version from the build only when the consumer hasn't set a real one (springdoc
        // pre-fills "v0", treated as unset). Never overwrites an explicit API version — a consumer that
        // versions their contract keeps it. When present, it changes per build, so the explorer's update
        // check has a value that moves on each release even if no endpoints changed.
        if (StringUtils.hasText(buildVersion)
                && (!StringUtils.hasText(info.getVersion()) || SPRINGDOC_DEFAULT_VERSION.equals(info.getVersion()))) {
            info.setVersion(buildVersion);
        }
        // Expose the service name only when we actually have one, and never overwrite a value the
        // consumer (or an earlier customizer run) already set.
        if (StringUtils.hasText(applicationName)
                && (info.getExtensions() == null || !info.getExtensions().containsKey("x-service-name"))) {
            info.addExtension("x-service-name", applicationName);
        }

        if (openApi.getComponents() == null) {
            openApi.setComponents(new Components());
        }
        // Idempotent: re-running the customizer (multiple groups, a disabled spec cache, or re-invoked
        // customizers) must not re-add the scheme or append a duplicate security requirement.
        var schemes = openApi.getComponents().getSecuritySchemes();
        if (schemes == null || !schemes.containsKey(SECURITY_SCHEME)) {
            openApi.getComponents().addSecuritySchemes(SECURITY_SCHEME,
                    new SecurityScheme()
                            .type(SecurityScheme.Type.APIKEY)
                            .in(SecurityScheme.In.HEADER)
                            .name("Authorization"));
        }
        if (!hasSecurityRequirement(openApi, SECURITY_SCHEME)) {
            openApi.addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
        }
    }

    /** Whether the document already requires the named security scheme (so we don't add it twice). */
    private static boolean hasSecurityRequirement(OpenAPI openApi, String scheme) {
        var security = openApi.getSecurity();
        return security != null && security.stream().anyMatch(req -> req.containsKey(scheme));
    }
}
