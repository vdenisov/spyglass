package org.plukh.spyglass.spring.webmvc;

import org.plukh.spyglass.spring.core.ExplorerAssets;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Maps the friendly explorer paths to its static entry point, and serves the explorer's assets with a
 * revalidate-on-reuse cache policy.
 *
 * <p>The assets ship with {@code spyglass-core} under {@code META-INF/resources/apidocs/} and Spring would
 * serve them on classpath presence alone, but its defaults send no {@code Cache-Control}, which lets
 * browsers cache the JS heuristically and miss a redeploy until a hard refresh. So a dedicated handler for
 * {@code /apidocs/**} is registered to attach {@code Cache-Control: no-cache} plus a content ETag (see
 * {@link ExplorerAssets} for the rationale). Its pattern is more specific than the default {@code /**}
 * handler, so it wins for the explorer assets while leaving all other static resources untouched.
 *
 * <p>The remaining mappings are the convenience redirects: each friendly path is a {@code 302 Found} to
 * the static entry point (matching the reactive adapter, which emits the same status for the same paths).
 *
 * <p>Mapping the context root to the explorer effectively replaces Swagger UI as the default API
 * documentation page for a consuming service. <strong>Note:</strong> this takes over {@code GET /} — a
 * host that serves its own {@code index.html} at the root should not rely on {@code /} once Spyglass is
 * activated (reach the explorer via {@code /apidocs/} instead).
 */
public class SpyglassWebConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve the explorer assets with no-cache + a content ETag so a redeploy is picked up without a
        // hard refresh. Last-Modified is disabled because the reproducible-build fat jar pins it (see
        // ExplorerAssets); the content ETag is the sole validator.
        registry.addResourceHandler(ExplorerAssets.PATH_PATTERN)
                .addResourceLocations(ExplorerAssets.CLASSPATH_LOCATION)
                .setCacheControl(ExplorerAssets.cacheControl())
                .setUseLastModified(false)
                .setEtagGenerator(ExplorerAssets.etagGenerator());
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Spring only resolves index.html for the context root, so map the friendly explorer paths to
        // the actual static entry point. The status is set explicitly so both adapters agree on 302.
        registry.addRedirectViewController("/", ExplorerAssets.ENTRY_POINT).setStatusCode(HttpStatus.FOUND);
        registry.addRedirectViewController("/apidocs", ExplorerAssets.ENTRY_POINT).setStatusCode(HttpStatus.FOUND);
        registry.addRedirectViewController("/apidocs/", ExplorerAssets.ENTRY_POINT).setStatusCode(HttpStatus.FOUND);
    }
}
