package org.plukh.spyglass.spring.webflux

import org.plukh.spyglass.spring.webflux.test.SpyglassReactiveTestApp
import org.plukh.spyglass.test.ManagementPortIsolationSpecBase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration

/**
 * Reactive binding of the shared {@link ManagementPortIsolationSpecBase}: boots
 * {@link SpyglassReactiveTestApp} with Actuator on a separate random management port. Also the empirical
 * confirmation that the reactive management child context stands up a general {@code DispatcherHandler}
 * (so the redirects/assets would leak there without the guard). springdoc is disabled because the test app
 * maps {@code /v3/api-docs} itself.
 */
@ContextConfiguration
@SpringBootTest(
        classes = SpyglassReactiveTestApp,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = [
                'springdoc.api-docs.enabled=false',
                'spring.main.web-application-type=reactive',
                'management.server.port=0',
                'management.endpoints.web.exposure.include=health'
        ]
)
class ManagementPortIsolationSpec extends ManagementPortIsolationSpecBase {
}
