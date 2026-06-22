package org.plukh.spyglass.spring.webflux;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.server.HandlerFunction;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

import java.net.URI;

import static org.springframework.web.reactive.function.server.RequestPredicates.GET;

/**
 * Maps the friendly explorer paths to its static entry point. The assets themselves are served by
 * Spring from {@code META-INF/resources/apidocs/} on classpath presence alone ({@code spyglass-core}
 * carries them), so no resource handler is registered here — only the convenience redirects.
 *
 * <p>WebFlux has no redirect view-controller (the servlet adapter's {@code addRedirectViewController}),
 * so the redirects are a {@link RouterFunction} bean instead. This composes as an ordinary bean and,
 * unlike a second {@code WebFluxConfigurer}, cannot clobber a host's global CORS configuration — which
 * matters for reactive services that register a permissive global {@code /**} CORS mapping. The
 * explicit {@code GET} routes take precedence over the catch-all static resource handler.
 *
 * <p>Mapping the context root to the explorer effectively replaces Swagger UI as the default API
 * documentation page for a consuming service.
 */
@Configuration(proxyBeanMethods = false)
public class SpyglassWebFluxConfig {

    @Bean
    public RouterFunction<ServerResponse> spyglassRedirects() {
        // Spring only resolves index.html for the context root, so map the friendly explorer paths to
        // the actual static entry point.
        HandlerFunction<ServerResponse> toIndex = request ->
                ServerResponse.temporaryRedirect(URI.create("/apidocs/index.html")).build();
        return RouterFunctions.route(GET("/"), toIndex)
                .andRoute(GET("/apidocs"), toIndex)
                .andRoute(GET("/apidocs/"), toIndex);
    }
}
