package org.plukh.spyglass.spring.webmvc.test

import org.plukh.spyglass.test.SpecFixtures
import org.springframework.boot.SpringBootConfiguration
import org.springframework.boot.autoconfigure.EnableAutoConfiguration
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.filter.ShallowEtagHeaderFilter

import java.util.concurrent.atomic.AtomicReference

/**
 * Minimal servlet app that serves a <em>mutable</em> OpenAPI spec at {@code /v3/api-docs} behind a real
 * {@link ShallowEtagHeaderFilter}, so the update check's opportunistic {@code If-None-Match} / {@code 304}
 * path can be exercised end-to-end — neither the demo nor {@link SpyglassTestApp} runs a spec validator,
 * so on those a spec poll always returns a fresh 200. The filter computes a content MD5 ETag and answers a
 * matching conditional poll with a bodyless 304; {@code POST /test/spec} swaps the served body, which
 * changes that ETag. The explorer's real static assets serve from the spyglass-core classpath as usual.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@RestController
class EtagSpecTestApp {

    static final String BASELINE_SPEC = SpecFixtures.specWithVersion('1.0.0')

    private final AtomicReference<String> spec = new AtomicReference<>(BASELINE_SPEC)

    @GetMapping(value = '/v3/api-docs', produces = MediaType.APPLICATION_JSON_VALUE)
    String spec() {
        spec.get()
    }

    @PostMapping('/test/spec')
    void setSpec(@RequestBody String body) {
        spec.set(body)
    }

    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> specEtagFilter() {
        def registration = new FilterRegistrationBean<ShallowEtagHeaderFilter>(new ShallowEtagHeaderFilter())
        registration.addUrlPatterns('/v3/api-docs')
        registration
    }
}
