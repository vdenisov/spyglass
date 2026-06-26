package org.plukh.spyglass.demo;

import io.swagger.v3.oas.models.info.Info;
import java.util.List;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the explorer demo controller, opt-in via {@code apidocs.demo.enabled=true} (default off).
 *
 * <p>For a consumer whose component scan does <em>not</em> cover {@code org.plukh.spyglass.demo} (e.g.
 * an extension pack's local demo in its own package), this gated {@code @Bean} is the only thing that
 * contributes the controller — so it stays off unless the flag is set. This demo app is itself
 * rooted at {@code org.plukh.spyglass.demo}, so its {@code @SpringBootApplication} additionally
 * component-scans {@link DemoController}; Spring keeps the scanned definition and drops this same-named
 * bean, which makes the flag a no-op <em>here</em> (the app sets it {@code true} regardless).
 *
 * <p>It is importable so other showcases (for example an extension pack's local demo) can reuse the
 * same consumer-neutral endpoints and layer their own on top, rather than duplicating them.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "apidocs.demo.enabled", havingValue = "true")
public class DemoEndpointsConfiguration {

    /** The same-origin path the sample extension is served from (META-INF/resources). */
    private static final String DEMO_EXTENSION = "/spyglass-ext/demo/index.js";

    @Bean
    public DemoController demoController() {
        return new DemoController();
    }

    @Bean
    public HealthController healthController() {
        return new HealthController();
    }

    /**
     * Advertises the bundled sample front-end extension to the explorer via the spec's
     * {@code x-spyglass-extensions} info extension — the spec-advertised channel the docs teach, and the
     * consumer-neutral twin of how an extension wires its own modules. The core loads
     * same-origin spec-advertised modules automatically, so the demo's extension appears with no
     * {@code ?ext=} query needed.
     *
     * <p>Additive (an {@code OpenApiCustomizer}, not an {@code OpenAPI} replacement) so it composes with
     * springdoc's defaults and the core {@code SpyglassOpenApiCustomizer}; the check-then-act guard
     * leaves any value a prior customizer set in place, mirroring the core customizer's idempotency
     * discipline.
     */
    @Bean
    public OpenApiCustomizer demoExtensionCustomizer() {
        return openApi -> {
            if (openApi.getInfo() == null) {
                openApi.setInfo(new Info());
            }
            var info = openApi.getInfo();
            if (info.getExtensions() == null || !info.getExtensions().containsKey("x-spyglass-extensions")) {
                info.addExtension("x-spyglass-extensions", List.of(DEMO_EXTENSION));
            }
        };
    }
}
