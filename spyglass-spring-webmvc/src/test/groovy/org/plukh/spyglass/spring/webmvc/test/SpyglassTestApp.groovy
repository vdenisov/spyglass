package org.plukh.spyglass.spring.webmvc.test

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

import java.nio.charset.StandardCharsets

/**
 * Minimal web context for the API explorer browser tests. It serves the explorer's real static
 * assets (which this module ships under {@code classpath:/META-INF/resources/apidocs}, auto-served by
 * Spring) and a synthetic OpenAPI spec at the path the explorer fetches ({@code /v3/api-docs}), and
 * nothing else.
 *
 * <p>It deliberately loads no database or upstream clients — only {@code spring-boot-starter-web} is on
 * the test classpath, so no persistence auto-configuration activates — keeping the suite fast and
 * exercising only the client-side explorer, independent of any service endpoint. The explorer specs
 * reference it explicitly via {@code @SpringBootTest(classes = SpyglassTestApp)}.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@RestController
class SpyglassTestApp {

    @GetMapping(value = '/v3/api-docs', produces = MediaType.APPLICATION_JSON_VALUE)
    String spec() {
        new ClassPathResource('apidocs-test/openapi-fixture.json')
                .inputStream.getText(StandardCharsets.UTF_8.name())
    }

    // A second fixture whose info advertises x-spyglass-extensions (one cross-origin, one relative), for
    // the ExtensionSeamAE test of the same-origin guard. Selected via the explorer's ?spec= override.
    @GetMapping(value = '/v3/api-docs-ext', produces = MediaType.APPLICATION_JSON_VALUE)
    String specWithExtensions() {
        new ClassPathResource('apidocs-test/openapi-ext-fixture.json')
                .inputStream.getText(StandardCharsets.UTF_8.name())
    }
}
