package org.plukh.spyglass.spring.webflux;

import org.plukh.spyglass.spring.core.ExplorerAssets;
import org.springframework.core.Ordered;
import org.springframework.core.io.Resource;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.resource.ResourceWebHandler;

import java.util.List;
import java.util.Map;

/**
 * Reactive-side helper for serving a static asset root with the explorer's revalidate-on-reuse cache policy
 * ({@code Cache-Control: no-cache} + a content ETag, {@code Last-Modified} disabled). The adapter serves the
 * convention roots ({@code /apidocs/**} and {@code /spyglass-ext/**}) through {@link #handler}, and an
 * extension serving from a non-conventional path applies the identical policy via {@link #mapping}.
 *
 * <p>The serving <em>policy</em> ({@link ExplorerAssets#cacheControl()},
 * {@link ExplorerAssets#etagGenerator()}) is stack-neutral and lives in {@code spyglass-spring-core}; only
 * the {@link ResourceWebHandler} wiring is reactive-specific, which is why this helper lives in the adapter
 * rather than on {@code ExplorerAssets}. The servlet adapter exposes the same policy through its own
 * {@code ExplorerAssetHandlers} twin (a {@code register(registry, …)} call — the two stacks share no
 * resource-registration type, so the signatures necessarily differ).
 */
public final class ExplorerAssetHandlers {

    /**
     * The handler-mapping order for the explorer's asset mappings: one step ahead of the default reactive
     * resource handler mapping (registered by {@code WebFluxConfigurationSupport} at
     * {@code LOWEST_PRECEDENCE - 1}), so a cache-configured mapping wins over it, while the
     * higher-precedence redirect {@code RouterFunction} still handles the exact friendly paths. The
     * adapter's built-in roots and every {@link #mapping} produced for a bespoke root share this single
     * order, so the invariant "between the redirects and the default resource handler" holds for all of
     * them from one place.
     */
    public static final int ASSET_MAPPING_ORDER = Ordered.LOWEST_PRECEDENCE - 2;

    private ExplorerAssetHandlers() {
    }

    /**
     * A {@link ResourceWebHandler} serving {@code location} with the explorer's cache policy:
     * {@code Cache-Control: no-cache} plus a content ETag, with {@code Last-Modified} disabled (it would be
     * pinned by a reproducible-build jar — see {@link ExplorerAssets}). The returned handler is fully
     * initialised ({@code afterPropertiesSet} has wired the default resolver chain) and ready to map.
     *
     * @param location the classpath asset directory to serve
     *
     * @return a configured, initialised resource handler
     *
     * @throws Exception if the handler fails to initialise
     */
    // Spring's setEtagGenerator accepts a generator that may return null (it then omits the ETag), but its
    // parameter type doesn't mark the function's String result @Nullable. Passing our honest
    // Function<Resource, @Nullable String> therefore trips NullAway on the JSpecify-annotated Spring (Boot 4)
    // leg; the suppression is scoped to this handler-wiring method.
    @SuppressWarnings("NullAway")
    public static ResourceWebHandler handler(Resource location) throws Exception {
        ResourceWebHandler handler = new ResourceWebHandler();
        handler.setLocations(List.of(location));
        handler.setCacheControl(ExplorerAssets.cacheControl());
        handler.setUseLastModified(false);
        handler.setEtagGenerator(ExplorerAssets.etagGenerator());
        // Wires the default PathResourceResolver chain; required before the handler can serve.
        handler.afterPropertiesSet();
        return handler;
    }

    /**
     * A {@link SimpleUrlHandlerMapping} that serves {@code pattern} from {@code location} with the
     * explorer's cache policy, registered at the shared {@link #ASSET_MAPPING_ORDER} so it sits between the
     * friendly redirect routes and the default reactive resource handler — the same order as the adapter's
     * built-in roots. A second mapping at that order is safe as long as its pattern is disjoint from the
     * built-in roots.
     *
     * @param pattern  the URL path pattern to serve (e.g. {@code /my-ext/**})
     * @param location the classpath asset directory to serve
     *
     * @return a handler mapping ready to register as a bean
     *
     * @throws Exception if the handler fails to initialise
     */
    public static SimpleUrlHandlerMapping mapping(String pattern, Resource location) throws Exception {
        return new SimpleUrlHandlerMapping(Map.of(pattern, handler(location)), ASSET_MAPPING_ORDER);
    }
}
