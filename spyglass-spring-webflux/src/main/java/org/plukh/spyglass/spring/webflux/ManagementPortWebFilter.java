package org.plukh.spyglass.spring.webflux;

import org.jspecify.annotations.Nullable;
import org.plukh.spyglass.spring.core.ExplorerAssets;
import org.plukh.spyglass.spring.core.ManagementPortGuard;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.PathContainer;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import org.springframework.web.util.pattern.PathPatternParser;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.stream.Stream;

/**
 * Declines the explorer surfaces on a separate Actuator management port. A {@link WebFilter} rather than a
 * per-mapping guard: the host's default resource handler is collected into the reactive management child
 * context (as is Spyglass's own asset mapping and redirect router), so the assets would still be served
 * there; a filter runs ahead of the {@code DispatcherHandler} and short-circuits regardless of which
 * handler would have resolved the path. It reaches the management context because the child's
 * {@code httpHandler} collects {@code WebFilter} beans including ancestors.
 *
 * <p>It stays a plain bean, <strong>not</strong> a {@code WebFluxConfigurer} (which could clobber a host's
 * global CORS, the reason the adapter's other pieces are plain beans too). On the primary port it is a pure
 * pass-through. The explorer paths are matched against {@link ExplorerAssets}' own definitions (the
 * redirect surfaces plus both asset roots), so the filter cannot drift from what is served; the arrival
 * port is checked first so primary traffic short-circuits before any path matching.
 */
final class ManagementPortWebFilter implements WebFilter {

    private final ManagementPortGuard guard;
    private final List<PathPattern> explorerPaths;

    ManagementPortWebFilter(ManagementPortGuard guard) {
        this.guard = guard;
        PathPatternParser parser = new PathPatternParser();
        this.explorerPaths = Stream.concat(
                        ExplorerAssets.REDIRECT_PATHS.stream(),
                        Stream.of(ExplorerAssets.PATH_PATTERN, ExplorerAssets.EXTENSION_PATH_PATTERN))
                .map(parser::parse)
                .toList();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (isManagementPort(exchange) && isExplorerPath(exchange)) {
            exchange.getResponse().setStatusCode(HttpStatus.NOT_FOUND);
            return exchange.getResponse().setComplete();
        }
        return chain.filter(exchange);
    }

    private boolean isManagementPort(ServerWebExchange exchange) {
        @Nullable InetSocketAddress local = exchange.getRequest().getLocalAddress();
        return local != null && guard.isManagementPort(local.getPort());
    }

    private boolean isExplorerPath(ServerWebExchange exchange) {
        PathContainer path = exchange.getRequest().getPath().pathWithinApplication();
        return explorerPaths.stream().anyMatch(pattern -> pattern.matches(path));
    }
}
