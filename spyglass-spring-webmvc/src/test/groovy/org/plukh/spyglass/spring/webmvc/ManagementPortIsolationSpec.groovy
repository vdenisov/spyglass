package org.plukh.spyglass.spring.webmvc

import org.plukh.spyglass.spring.webmvc.test.SpringdocTestApp
import org.plukh.spyglass.test.ManagementPortIsolationSpecBase
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration

/**
 * Servlet binding of the shared {@link ManagementPortIsolationSpecBase}: boots {@link SpringdocTestApp}
 * (Spyglass wired via its real entry point, springdoc enabled) with Actuator on a separate random
 * management port, and asserts the explorer doesn't leak onto it while the primary port is unchanged.
 */
@ContextConfiguration
@SpringBootTest(
        classes = SpringdocTestApp,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = [
                'management.server.port=0',
                'management.endpoints.web.exposure.include=health'
        ]
)
class ManagementPortIsolationSpec extends ManagementPortIsolationSpecBase {
}
