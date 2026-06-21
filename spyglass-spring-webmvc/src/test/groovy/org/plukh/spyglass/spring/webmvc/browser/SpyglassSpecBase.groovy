package org.plukh.spyglass.spring.webmvc.browser

import org.plukh.spyglass.spring.webmvc.test.SpyglassTestApp
import org.plukh.spyglass.test.ExplorerBrowserSpecBase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration

/**
 * Servlet (Spring MVC) boot context for the explorer browser specs. Boots the minimal
 * {@link SpyglassTestApp} on a random port; the Playwright lifecycle and the generic explorer helpers
 * are inherited from {@link ExplorerBrowserSpecBase} (the shared {@code spyglass-spring-test-support}
 * harness). Only the servlet boot wiring and the fixture-specific {@code fillRequiredWidget} live here.
 */
@ContextConfiguration
@SpringBootTest(
        classes = [SpyglassTestApp],
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = [
                'springdoc.api-docs.enabled=false',
                'spring.main.web-application-type=servlet'
        ]
)
abstract class SpyglassSpecBase extends ExplorerBrowserSpecBase {

    /** Fills the three required CreateWidget fields so POST /widgets builds a representative body. */
    protected void fillRequiredWidget(String name = 'W', String priority = 'HIGH', String count = '3') {
        textInput('name').fill(name)
        selectInput('priority').selectOption(priority)
        numberInput('count').fill(count)
    }
}
