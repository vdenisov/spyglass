package org.plukh.spyglass.spring.webmvc

import org.plukh.spyglass.spring.webmvc.test.SpringdocTestApp
import org.plukh.spyglass.test.ExplorerAssetCachingSpecBase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration

/**
 * Servlet binding of the shared {@link ExplorerAssetCachingSpecBase}: boots {@link SpringdocTestApp}
 * (Spyglass wired via its real entry point) on a random port and runs the inherited cache-policy checks.
 */
@ContextConfiguration
@SpringBootTest(
        classes = SpringdocTestApp,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT
)
class ExplorerAssetCachingSpec extends ExplorerAssetCachingSpecBase {
}
