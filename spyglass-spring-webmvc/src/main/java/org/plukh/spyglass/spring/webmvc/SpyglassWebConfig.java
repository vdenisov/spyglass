package org.plukh.spyglass.spring.webmvc;

import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Maps the friendly explorer paths to its static entry point. The assets themselves are served by
 * Spring from {@code META-INF/resources/apidocs/} on classpath presence alone ({@code spyglass-core}
 * carries them), so no resource handler is registered here — only the convenience redirects. Each is a
 * {@code 302 Found} (matching the reactive adapter, which emits the same status for the same paths).
 *
 * <p>Mapping the context root to the explorer effectively replaces Swagger UI as the default API
 * documentation page for a consuming service. <strong>Note:</strong> this takes over {@code GET /} — a
 * host that serves its own {@code index.html} at the root should not rely on {@code /} once Spyglass is
 * activated (reach the explorer via {@code /apidocs/} instead).
 */
public class SpyglassWebConfig implements WebMvcConfigurer {

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Spring only resolves index.html for the context root, so map the friendly explorer paths to
        // the actual static entry point. The status is set explicitly so both adapters agree on 302.
        registry.addRedirectViewController("/", "/apidocs/index.html").setStatusCode(HttpStatus.FOUND);
        registry.addRedirectViewController("/apidocs", "/apidocs/index.html").setStatusCode(HttpStatus.FOUND);
        registry.addRedirectViewController("/apidocs/", "/apidocs/index.html").setStatusCode(HttpStatus.FOUND);
    }
}
