package org.plukh.spyglass.demo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the explorer demo controller, opt-in via {@code apidocs.demo.enabled=true} (default off).
 * The controller lives outside any consumer's component-scan base package, so it is bound only through
 * this explicit, gated bean — never by default.
 *
 * <p>It is importable so other showcases (for example an extension pack's local demo) can reuse the
 * same organization-neutral endpoints and layer their own on top, rather than duplicating them.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "apidocs.demo.enabled", havingValue = "true")
public class DemoEndpointsConfiguration {

    @Bean
    public DemoController demoController() {
        return new DemoController();
    }
}
