package org.plukh.spyglass.spring.webflux.test

import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.autoconfigure.data.jdbc.JdbcRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.autoconfigure.liquibase.LiquibaseAutoConfiguration
import org.springframework.context.annotation.Import
import org.springframework.core.io.ClassPathResource
import org.springframework.http.MediaType
import org.plukh.spyglass.spring.webflux.SpyglassConfiguration
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

import java.nio.charset.StandardCharsets

/**
 * Minimal reactive (WebFlux) web context for the API explorer smoke spec. The reactive twin of the
 * servlet module's {@code SpyglassTestApp}: it serves the explorer's real static assets (shipped under
 * {@code classpath:/META-INF/resources/apidocs}, auto-served by Spring's reactive defaults) and a
 * synthetic OpenAPI spec at the path the explorer fetches ({@code /v3/api-docs}), and nothing else.
 *
 * <p>It deliberately does not load a database or any upstream clients — the JDBC/Liquibase
 * auto-configurations are switched off — so the suite stays fast and exercises only that the explorer
 * loads and executes over the reactive serving stack (reactor-netty). The spec references it explicitly
 * via {@code @SpringBootTest(classes = SpyglassReactiveTestApp)}.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(exclude = [
        DataSourceAutoConfiguration,
        DataSourceTransactionManagerAutoConfiguration,
        JdbcRepositoriesAutoConfiguration,
        LiquibaseAutoConfiguration
])
@Import(SpyglassConfiguration)
@RestController
class SpyglassReactiveTestApp {

    @GetMapping(value = '/v3/api-docs', produces = MediaType.APPLICATION_JSON_VALUE)
    String spec() {
        new ClassPathResource('apidocs-test/openapi-fixture.json')
                .inputStream.getText(StandardCharsets.UTF_8.name())
    }
}
