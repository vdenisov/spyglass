package org.plukh.spyglass.demo;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the explorer demo controller, opt-in via {@code apidocs.demo.enabled=true} (default off).
 *
 * <p>For a consumer whose component scan does <em>not</em> cover {@code org.plukh.spyglass.demo} (e.g.
 * an extension pack's local demo in its own package), this gated {@code @Bean} is the only thing that
 * contributes the controller — so it stays off unless the flag is set. This OSS demo app is itself
 * rooted at {@code org.plukh.spyglass.demo}, so its {@code @SpringBootApplication} additionally
 * component-scans {@link DemoController}; Spring keeps the scanned definition and drops this same-named
 * bean, which makes the flag a no-op <em>here</em> (the app sets it {@code true} regardless).
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
