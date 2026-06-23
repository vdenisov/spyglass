package org.plukh.spyglass.demo.browser

import org.plukh.spyglass.demo.SpyglassDemoApplication
import org.plukh.spyglass.test.ExplorerBrowserSpecBase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration

/**
 * Servlet boot context for the demo's explorer browser spec(s). Boots the real
 * {@link SpyglassDemoApplication} on a random port so springdoc generates {@code /v3/api-docs} from the
 * demo endpoints (including the {@code x-spyglass-extensions} info extension that advertises the bundled
 * sample extension); the Playwright lifecycle and the generic explorer helpers are inherited from
 * {@link ExplorerBrowserSpecBase}. The demo extension is served same-origin from {@code META-INF/resources},
 * so it auto-loads with no {@code ?ext=} query.
 */
@ContextConfiguration
@SpringBootTest(
        classes = [SpyglassDemoApplication],
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = ['apidocs.demo.enabled=true']
)
abstract class SpyglassDemoSpecBase extends ExplorerBrowserSpecBase {
}
