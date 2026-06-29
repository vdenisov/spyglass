package org.plukh.spyglass.spring.webmvc;

import org.plukh.spyglass.spring.core.ExplorerAssets;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;

/**
 * Servlet-side helper for serving a static asset root with the explorer's revalidate-on-reuse cache policy
 * ({@code Cache-Control: no-cache} + a content ETag, {@code Last-Modified} disabled). The adapter registers
 * the convention roots ({@code /apidocs/**} and {@code /spyglass-ext/**}) through it, and an extension
 * serving from a non-conventional path applies the identical policy in one call.
 *
 * <p>The serving <em>policy</em> ({@link ExplorerAssets#cacheControl()},
 * {@link ExplorerAssets#etagGenerator()}) is stack-neutral and lives in {@code spyglass-spring-core}; only
 * the registration call is servlet-specific, which is why this helper lives in the adapter rather than on
 * {@code ExplorerAssets}. The reactive adapter exposes the same policy through its own
 * {@code ExplorerAssetHandlers} twin (different signatures — a {@code ResourceWebHandler} factory — since
 * the two stacks share no resource-registration type).
 */
public final class ExplorerAssetHandlers {

    private ExplorerAssetHandlers() {
    }

    /**
     * Registers a resource handler that serves {@code pattern} from {@code classpathLocation} with the
     * explorer's cache policy: {@code Cache-Control: no-cache} plus a content ETag, with
     * {@code Last-Modified} disabled (it would be pinned by a reproducible-build jar — see
     * {@link ExplorerAssets}). Mapping a location with no resources present simply 404s, which is harmless.
     *
     * @param registry         the servlet resource-handler registry to register on
     * @param pattern          the URL path pattern to serve (e.g. {@code /spyglass-ext/**})
     * @param classpathLocation the {@code classpath:} location string holding the assets
     */
    // Spring's setEtagGenerator accepts a generator that may return null (it then omits the ETag), but its
    // parameter type doesn't mark the function's String result @Nullable. Passing our honest
    // Function<Resource, @Nullable String> therefore trips NullAway on the JSpecify-annotated Spring (Boot 4)
    // leg; the suppression is scoped to this one-line wiring call.
    @SuppressWarnings("NullAway")
    public static void register(ResourceHandlerRegistry registry, String pattern, String classpathLocation) {
        registry.addResourceHandler(pattern)
                .addResourceLocations(classpathLocation)
                .setCacheControl(ExplorerAssets.cacheControl())
                .setUseLastModified(false)
                .setEtagGenerator(ExplorerAssets.etagGenerator());
    }
}
