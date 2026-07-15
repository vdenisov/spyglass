package org.plukh.spyglass.spring.webflux;

import org.plukh.spyglass.spring.core.ExplorerAssets;
import org.plukh.spyglass.spring.core.ManagementPortGuard;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.resource.ResourceWebHandler;
import org.springframework.web.server.WebFilter;

import java.net.URI;
import java.util.Map;

/**
 * Maps the friendly explorer paths to its static entry point, and serves the explorer's assets with a
 * revalidate-on-reuse cache policy.
 *
 * <p>The assets ship with {@code spyglass-core} under {@code META-INF/resources/apidocs/} and Spring would
 * serve them on classpath presence alone, but its defaults send no {@code Cache-Control}, which lets
 * browsers cache the JS heuristically and miss a redeploy until a hard refresh. So a dedicated handler for
 * {@code /apidocs/**} attaches {@code Cache-Control: no-cache} plus a content ETag (see
 * {@link ExplorerAssets} for the rationale). The same policy is mapped for {@code /spyglass-ext/**} so every
 * front-end extension's assets are served fresh too — one handler enrols every extension on the classpath,
 * with no per-extension config. An extension serving from a non-conventional path can apply the identical
 * policy via {@link ExplorerAssetHandlers#mapping}.
 *
 * <p>Both pieces are plain beans rather than a {@code WebFluxConfigurer}: the latter, unlike an ordinary
 * bean, can clobber a host's global CORS configuration — which matters for reactive services that register
 * a permissive global {@code /**} CORS mapping. So the redirects are a {@link RouterFunction} (WebFlux has
 * no redirect view-controller), and the asset cache policy is a {@link SimpleUrlHandlerMapping} carrying a
 * pre-configured {@link ResourceWebHandler}. The mapping is ordered just ahead of the default reactive
 * resource handler so it wins for {@code /apidocs/**}, while the higher-precedence redirect routes still
 * handle the exact {@code /apidocs} and {@code /apidocs/} paths. Each redirect is a {@code 302 Found}
 * (matching the servlet adapter, which emits the same status for the same paths).
 *
 * <p>Mapping the context root to the explorer effectively replaces Swagger UI as the default API
 * documentation page for a consuming service. <strong>Note:</strong> this takes over {@code GET /} — a
 * host that serves its own {@code index.html} at the root should not rely on {@code /} once Spyglass is
 * activated (reach the explorer via {@code /apidocs/} instead).
 *
 * <p>These mappings are meant for the primary web server only. When a host runs Actuator on a separate
 * {@code management.server.port}, Boot's reactive management child context collects these beans (and the
 * host's default resource handler) from the ancestor context, so the explorer would otherwise leak onto the
 * admin port. {@link #spyglassManagementPortGuard} adds a {@link ManagementPortWebFilter} that {@code 404}s
 * the explorer paths on that port; the primary port is unaffected.
 */
@Configuration(proxyBeanMethods = false)
public class SpyglassWebFluxConfig {

    @Bean
    public RouterFunction<ServerResponse> spyglassRedirects() {
        // Spring only resolves index.html for the context root, so map the friendly explorer paths to
        // the actual static entry point. A 302 Found, to agree with the servlet adapter's status.
        HandlerFunction<ServerResponse> toIndex = request ->
                ServerResponse.status(HttpStatus.FOUND).location(URI.create(ExplorerAssets.ENTRY_POINT)).build();
        RouterFunctions.Builder routes = RouterFunctions.route();
        for (String path : ExplorerAssets.REDIRECT_PATHS) {
            routes.GET(path, toIndex);
        }
        return routes.build();
    }

    @Bean
    public SimpleUrlHandlerMapping spyglassAssetHandlerMapping() throws Exception {
        // Serve the explorer's own assets and every extension's assets with no-cache + a content ETag so a
        // redeploy is picked up without a hard refresh. Last-Modified is disabled because the
        // reproducible-build fat jar pins it (see ExplorerAssets); the content ETag is the sole validator.
        // Both convention roots share one mapping (distinct patterns, one handler each), at the shared
        // ExplorerAssetHandlers.ASSET_MAPPING_ORDER (see there for the load-bearing ordering invariant).
        // Note: precedence here is purely by integer order — unlike the servlet adapter, where the
        // ResourceHandlerRegistry tie-breaks these over /** by pattern specificity — so a host mapping at
        // this order or higher precedence with an overlapping pattern would shadow this one and silently
        // restore the default (no-cache-header) serving.
        return new SimpleUrlHandlerMapping(Map.of(
                ExplorerAssets.PATH_PATTERN, ExplorerAssetHandlers.handler(ExplorerAssets.location()),
                ExplorerAssets.EXTENSION_PATH_PATTERN, ExplorerAssetHandlers.handler(ExplorerAssets.extensionLocation())),
                ExplorerAssetHandlers.ASSET_MAPPING_ORDER);
    }

    @Bean
    public WebFilter spyglassManagementPortGuard(ManagementPortGuard guard) {
        // Keep the explorer off a separate management port (see ManagementPortWebFilter). Stays a plain
        // WebFilter bean, not a WebFluxConfigurer, for the same CORS reason as the beans above; on the
        // primary port it is a pure pass-through.
        return new ManagementPortWebFilter(guard);
    }
}
