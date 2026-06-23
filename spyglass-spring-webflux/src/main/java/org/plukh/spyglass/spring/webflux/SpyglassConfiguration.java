package org.plukh.spyglass.spring.webflux;

import org.plukh.spyglass.spring.core.SpyglassCoreConfiguration;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * Single entry point that wires Spyglass into a reactive (Spring WebFlux) host service. A consumer
 * activates everything with one declaration:
 *
 * <pre>{@code @Import(SpyglassConfiguration.class)}</pre>
 *
 * <p>This follows the explicit-import activation style: there is no {@code AutoConfiguration.imports}
 * or {@code spring.factories} in this jar. The explorer's static assets are served independently by
 * Spring from {@code META-INF/resources/apidocs/} as soon as {@code spyglass-core} is on the
 * classpath, so this configuration only wires the Java beans:
 *
 * <ul>
 *   <li>the stack-neutral core wiring — the additive springdoc OpenAPI customizer
 *       (see {@link SpyglassCoreConfiguration});</li>
 *   <li>the root/{@code /apidocs} redirects to the static entry point (see {@link SpyglassWebFluxConfig}).</li>
 * </ul>
 *
 * <p>This is the reactive twin of the servlet adapter's entry point; both expose the same
 * {@code SpyglassConfiguration} contract so a consumer activates the explorer identically regardless of
 * its web stack. Consumer-specific features — for example an auth-token generator, header presets, or
 * environment-specific deep links — are not wired here; they are contributed by a separate extension
 * pack that imports this configuration and registers its own UI through the front-end extension seam.
 */
@Configuration(proxyBeanMethods = false)
@Import({
        SpyglassCoreConfiguration.class,
        SpyglassWebFluxConfig.class
})
public class SpyglassConfiguration {
}
