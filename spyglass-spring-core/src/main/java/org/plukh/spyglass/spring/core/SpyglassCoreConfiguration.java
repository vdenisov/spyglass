package org.plukh.spyglass.spring.core;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Stack-neutral Spring wiring for Spyglass, shared by the servlet and reactive adapters. It registers
 * the additive, vendor-neutral OpenAPI customizer (security scheme + default title + the generic
 * {@code x-service-name} info extension).
 *
 * <p>This is imported by the web adapter's single entry point (e.g.
 * {@code org.plukh.spyglass.spring.webmvc.SpyglassConfiguration}); the adapter contributes the
 * stack-specific pieces (friendly redirects, asset serving) on top.
 */
@Configuration(proxyBeanMethods = false)
public class SpyglassCoreConfiguration {

    @Bean
    public SpyglassOpenApiCustomizer spyglassOpenApiCustomizer(
            @Value("${spring.application.name:}") String applicationName) {
        return new SpyglassOpenApiCustomizer(applicationName);
    }
}
