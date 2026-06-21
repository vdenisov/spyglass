package org.plukh.spyglass.spring.webflux.browser

import org.plukh.spyglass.spring.webflux.test.SpyglassReactiveTestApp
import org.plukh.spyglass.test.ExplorerBrowserSpecBase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration

/**
 * Reactive (WebFlux) boot context for the per-flavor smoke spec. Boots the minimal
 * {@link SpyglassReactiveTestApp} on a random port (a reactive context); the Playwright lifecycle and
 * the generic explorer helpers are inherited from {@link ExplorerBrowserSpecBase} (the shared
 * {@code spyglass-spring-test-support} harness). {@code WebServerApplicationContext} resolves the random
 * port for the reactive context just as it does for the servlet one.
 */
@ContextConfiguration
@SpringBootTest(
        classes = [SpyglassReactiveTestApp],
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = [
                'springdoc.api-docs.enabled=false',
                'spring.main.web-application-type=reactive'
        ]
)
abstract class SpyglassReactiveSpecBase extends ExplorerBrowserSpecBase {
}
