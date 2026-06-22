package org.plukh.spyglass.spring.webmvc.test

import org.plukh.spyglass.spring.webmvc.SpyglassConfiguration
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.context.annotation.Import
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * A minimal servlet app with springdoc <em>enabled</em> and Spyglass wired through its real entry point
 * ({@link SpyglassConfiguration}) plus one trivial endpoint, so the generated {@code /v3/api-docs}
 * exercises the customizer through the actual springdoc SPI and bean discovery. Used by
 * {@code SpyglassSpringdocIntegrationSpec}; distinct from {@code SpyglassTestApp}, which disables
 * springdoc and serves a hand-written fixture for the browser specs.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(SpyglassConfiguration)
@RestController
class SpringdocTestApp {

    @GetMapping('/widgets/{id}')
    Map<String, String> widget(@PathVariable String id) {
        [id: id]
    }
}
