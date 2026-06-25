package org.plukh.spyglass.spring.webflux;

import org.plukh.spyglass.spring.core.ExplorerAssets;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.resource.ResourceWebHandler;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

/**
 * Maps the friendly explorer paths to its static entry point, and serves the explorer's assets with a
 * revalidate-on-reuse cache policy.
 *
 * <p>The assets ship with {@code spyglass-core} under {@code META-INF/resources/apidocs/} and Spring would
 * serve them on classpath presence alone, but its defaults send no {@code Cache-Control}, which lets
 * browsers cache the JS heuristically and miss a redeploy until a hard refresh. So a dedicated handler for
 * {@code /apidocs/**} attaches {@code Cache-Control: no-cache} plus a content ETag (see
 * {@link ExplorerAssets} for the rationale).
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
 */
@Configuration(proxyBeanMethods = false)
public class SpyglassWebFluxConfig {

    @Bean
    public RouterFunction<ServerResponse> spyglassRedirects() {
        // Spring only resolves index.html for the context root, so map the friendly explorer paths to
        // the actual static entry point. A 302 Found, to agree with the servlet adapter's status.
        HandlerFunction<ServerResponse> toIndex = request ->
                ServerResponse.status(HttpStatus.FOUND).location(URI.create(ExplorerAssets.ENTRY_POINT)).build();
        return RouterFunctions.route(GET("/"), toIndex)
                .andRoute(GET("/apidocs"), toIndex)
                .andRoute(GET("/apidocs/"), toIndex);
    }

    @Bean
    public SimpleUrlHandlerMapping spyglassAssetHandlerMapping() throws Exception {
        // Serve the explorer assets with no-cache + a content ETag so a redeploy is picked up without a
        // hard refresh. Last-Modified is disabled because the reproducible-build fat jar pins it (see
        // ExplorerAssets); the content ETag is the sole validator.
        ResourceWebHandler handler = new ResourceWebHandler();
        handler.setLocations(List.of(ExplorerAssets.location()));
        handler.setCacheControl(ExplorerAssets.cacheControl());
        handler.setUseLastModified(false);
        handler.setEtagGenerator(ExplorerAssets.etagGenerator());
        // Wires the default PathResourceResolver chain; required before the handler can serve.
        handler.afterPropertiesSet();
        // Load-bearing ordering invariant: one step ahead of the default reactive resource handler mapping
        // (registered by WebFluxConfigurationSupport at LOWEST_PRECEDENCE - 1) so our cache-configured
        // handler wins for /apidocs/**, while the redirect RouterFunction (RouterFunctionMapping, order -1)
        // still handles the exact /apidocs and /apidocs/ paths. Unlike the servlet adapter — where the
        // ResourceHandlerRegistry tie-breaks /apidocs/** over /** by pattern specificity — precedence here
        // is purely by integer order, so a host mapping registered at this order or higher precedence with
        // an overlapping pattern would shadow this one and silently restore the default (no-cache-header)
        // serving. Keep this strictly between the redirect mapping and the default resource mapping.
        return new SimpleUrlHandlerMapping(
                Map.of(ExplorerAssets.PATH_PATTERN, handler), Ordered.LOWEST_PRECEDENCE - 2);
    }
}
