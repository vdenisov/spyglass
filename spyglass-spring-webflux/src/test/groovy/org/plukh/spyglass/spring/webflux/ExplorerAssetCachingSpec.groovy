package org.plukh.spyglass.spring.webflux

import org.plukh.spyglass.spring.webflux.test.SpyglassReactiveTestApp
import org.plukh.spyglass.test.ExplorerAssetCachingSpecBase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration

/**
 * Reactive binding of the shared {@link ExplorerAssetCachingSpecBase}: boots {@link SpyglassReactiveTestApp}
 * on a random port and runs the inherited cache-policy checks. springdoc is disabled because the test app
 * maps {@code /v3/api-docs} itself.
 */
@ContextConfiguration
@SpringBootTest(
        classes = SpyglassReactiveTestApp,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = [
                'springdoc.api-docs.enabled=false',
                'spring.main.web-application-type=reactive'
        ]
)
class ExplorerAssetCachingSpec extends ExplorerAssetCachingSpecBase {
}
