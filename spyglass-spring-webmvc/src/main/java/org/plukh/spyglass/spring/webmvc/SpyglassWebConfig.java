package org.plukh.spyglass.spring.webmvc;

import org.plukh.spyglass.spring.core.ExplorerAssets;
import org.plukh.spyglass.spring.core.ManagementPortGuard;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
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
 * <p>The same policy is registered for {@code /spyglass-ext/**} so every front-end extension's assets are
 * served fresh too — the classpath merges {@code META-INF/resources/spyglass-ext/} across extension jars,
 * so one handler enrols them all with no per-extension config. An extension serving from a non-conventional
 * path can apply the identical policy via {@link ExplorerAssetHandlers#register}.
 *
 * <p>The remaining mappings are the convenience redirects: each friendly path is a {@code 302 Found} to
 * the static entry point (matching the reactive adapter, which emits the same status for the same paths).
 *
 * <p>Mapping the context root to the explorer effectively replaces Swagger UI as the default API
 * documentation page for a consuming service. <strong>Note:</strong> this takes over {@code GET /} — a
 * host that serves its own {@code index.html} at the root should not rely on {@code /} once Spyglass is
 * activated (reach the explorer via {@code /apidocs/} instead).
 *
 * <p>These mappings are meant for the primary web server only. When a host runs Actuator on a separate
 * {@code management.server.port}, Boot's management child context collects the host's
 * {@code WebMvcConfigurer}s (this one <em>and</em> Boot's own default {@code /**} static handler) from the
 * ancestor context, so the explorer would otherwise leak onto the admin port. {@link #addInterceptors} adds
 * a {@link ManagementPortInterceptor} over the explorer paths to {@code 404} them on that port; the primary
 * port is unaffected.
 */
public class SpyglassWebConfig implements WebMvcConfigurer {

    private final ManagementPortGuard guard;

    public SpyglassWebConfig(ManagementPortGuard guard) {
        this.guard = guard;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Serve the explorer's own assets and every extension's assets with no-cache + a content ETag so a
        // redeploy is picked up without a hard refresh. Last-Modified is disabled because the
        // reproducible-build fat jar pins it (see ExplorerAssets); the content ETag is the sole validator.
        ExplorerAssetHandlers.register(registry, ExplorerAssets.PATH_PATTERN, ExplorerAssets.CLASSPATH_LOCATION);
        ExplorerAssetHandlers.register(registry,
                ExplorerAssets.EXTENSION_PATH_PATTERN, ExplorerAssets.EXTENSION_CLASSPATH_LOCATION);
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        // Spring only resolves index.html for the context root, so map the friendly explorer paths to
        // the actual static entry point. The status is set explicitly so both adapters agree on 302.
        for (String path : ExplorerAssets.REDIRECT_PATHS) {
            registry.addRedirectViewController(path, ExplorerAssets.ENTRY_POINT).setStatusCode(HttpStatus.FOUND);
        }
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Keep the explorer off a separate management port (see ManagementPortInterceptor). A mapped
        // interceptor, not narrower registration: the host's default /** static handler is collected into
        // the management context too and would still serve the assets there, so the surfaces must be
        // declined at request time. Registering over the same paths ExplorerAssets defines (the redirect
        // surfaces plus both asset roots) keeps this from drifting from what is actually served.
        registry.addInterceptor(new ManagementPortInterceptor(guard))
                .addPathPatterns(ExplorerAssets.REDIRECT_PATHS)
                .addPathPatterns(ExplorerAssets.PATH_PATTERN, ExplorerAssets.EXTENSION_PATH_PATTERN);
    }
}
