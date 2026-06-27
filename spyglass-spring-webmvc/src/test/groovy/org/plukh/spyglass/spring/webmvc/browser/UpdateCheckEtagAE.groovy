package org.plukh.spyglass.spring.webmvc.browser

import com.microsoft.playwright.Response
import org.plukh.spyglass.spring.webmvc.test.EtagSpecTestApp
import org.plukh.spyglass.test.ExplorerBrowserSpecBase
import org.plukh.spyglass.test.SpecFixtures
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration

import java.util.function.Consumer

/**
 * Proves the update check's opportunistic {@code If-None-Match} / {@code 304} path end-to-end against a
 * real {@link EtagSpecTestApp} running a {@link org.springframework.web.filter.ShallowEtagHeaderFilter} on
 * {@code /v3/api-docs} — the one branch the demo and {@code SpyglassTestApp} can't exercise (they serve no
 * spec validator). It confirms that our {@code cache:'no-store'} + manually-set {@code If-None-Match}
 * actually yields a genuine bodyless {@code 304} (rather than a synthesized 200-from-cache) which the spec
 * probe reads as "unchanged", and that mutating the served spec flips the ETag so a fresh {@code 200}
 * surfaces the toast.
 */
@ContextConfiguration
@SpringBootTest(
        classes = [EtagSpecTestApp],
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = [
                'springdoc.api-docs.enabled=false',
                'spring.main.web-application-type=servlet'
        ]
)
class UpdateCheckEtagAE extends ExplorerBrowserSpecBase {

    private static final String FAST = 'updateCheckInterval=0.15&updateCheckWindow=0.3'

    def "an unchanged spec answers conditional polls with 304 and never surfaces the toast"() {
        given: 'spec responses are observed'
        def statuses = Collections.synchronizedList([] as List<Integer>)
        page.onResponse({ Response r -> if (r.url().contains('/v3/api-docs')) statuses.add(r.status()) } as Consumer<Response>)

        when:
        page.navigate("/apidocs/index.html?${FAST}")
        page.waitForSelector('.sidebar .op-link')
        page.waitForTimeout(1500)

        then: 'the real filter answered at least one conditional poll with a genuine 304, and nothing surfaced'
        statuses.any { it == 304 }
        page.locator('.update-toast').count() == 0
    }

    def "a changed spec returns a fresh 200 with a new ETag and surfaces the toast"() {
        when: 'the baseline loads and the steady-state 304 polling settles'
        page.navigate("/apidocs/index.html?${FAST}")
        page.waitForSelector('.sidebar .op-link')
        page.waitForTimeout(400)

        and: 'the server-side spec is swapped for different bytes (a different content ETag)'
        page.evaluate("async (body) => { await fetch('/test/spec', { method: 'POST', headers: { 'Content-Type': 'text/plain' }, body }) }",
                SpecFixtures.specWithVersion('2.0.0'))

        then: 'the next conditional poll no longer matches, the filter returns 200 with fresh bytes, and the toast surfaces'
        page.waitForSelector('.update-toast')
    }
}
